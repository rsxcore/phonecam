mod decoder;
mod identity;
mod nv12;
mod proto;
mod shm;

use std::io::BufRead;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

/// Work handed from the network thread to the decode thread.
enum Job {
    Config(proto::Config),
    Frame(proto::Frame),
    Lost(String),
}

/// Status goes to stdout as JSON lines (for the desktop UI) and, for the
/// legacy Win32 client, as plain text to an optional status file.
struct Reporter {
    status_file: Option<String>,
}

impl Reporter {
    fn emit(&self, event: Value) {
        println!("{event}");
        if let (Some(path), Some(text)) = (&self.status_file, event.get("text").and_then(Value::as_str)) {
            let _ = std::fs::write(path, text);
        }
    }

    fn status(&self, text: &str) {
        self.emit(json!({ "event": "status", "text": text }));
    }
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let value = |key: &str| args.iter().position(|a| a == key).and_then(|i| args.get(i + 1)).cloned();
    let reporter = Arc::new(Reporter { status_file: value("--status") });

    if let Err(e) = decoder::init_thread() {
        reporter.status(&e);
        std::process::exit(1);
    }
    let shm = match shm::Shm::create() {
        Ok(s) => Arc::new(Mutex::new(s)),
        Err(e) => {
            reporter.status(&e);
            std::process::exit(1);
        }
    };

    if args.iter().any(|a| a == "--test") {
        run_test_pattern(&shm, &reporter);
    }

    let address = value("--connect").or_else(|| value("--url"));
    let address = match address.as_deref().map(proto::parse_address) {
        Some(Ok(a)) => Some(a),
        Some(Err(e)) => {
            reporter.status(&e);
            std::process::exit(1);
        }
        None => None,
    };
    reporter.emit(json!({
        "event": "codecs",
        "h264": decoder::supports(proto::Codec::H264),
        "hevc": decoder::supports(proto::Codec::Hevc),
    }));

    let identity = match identity::Identity::load_or_create() {
        Ok(i) => i,
        Err(e) => {
            reporter.status(&format!("Cannot create the PC identity: {e}"));
            std::process::exit(1);
        }
    };
    let tls = identity.client_config().expect("TLS configuration");
    reporter.emit(json!({ "event": "identity", "name": identity::computer_name(), "fingerprint": identity.fingerprint }));

    let (tx, rx) = mpsc::sync_channel::<Job>(16);
    let skip_until_key = Arc::new(AtomicBool::new(false));
    {
        let (shm, reporter, skip) = (shm.clone(), reporter.clone(), skip_until_key.clone());
        thread::spawn(move || decode_loop(rx, shm, reporter, skip));
    }

    // Commands from the desktop UI: one JSON object per line, forwarded as-is.
    let control: Arc<Mutex<Option<proto::Sender>>> = Arc::new(Mutex::new(None));
    {
        let control = control.clone();
        thread::spawn(move || {
            for line in std::io::stdin().lock().lines().map_while(Result::ok) {
                if serde_json::from_str::<Value>(&line).is_err() {
                    continue;
                }
                if let Some(sender) = control.lock().unwrap().as_ref() {
                    sender.send(proto::CONTROL, line.as_bytes());
                }
            }
        });
    }

    loop {
        let target = match &address {
            Some(a) => Some(a.clone()),
            None => find_phone(&reporter),
        };
        let result = match target {
            Some(t) => {
                reporter.status(&format!("Connecting to {t}…"));
                receive(&t, &tls, &identity, &tx, &skip_until_key, &control, &reporter)
            }
            None => Err("Phone not found. Open PhoneCam on the phone (same Wi-Fi).".into()),
        };
        *control.lock().unwrap() = None;
        let error = result.err().unwrap_or_else(|| "Disconnected".into());
        let _ = tx.send(Job::Lost(error.clone()));
        reporter.status(&format!("Reconnecting: {error}"));
        thread::sleep(Duration::from_secs(1));
    }
}

/// Paired phones first (by the fingerprint prefix they announce), then the
/// addresses that worked before, then any phone that answers, then a scan of
/// the local network for when the firewall swallows discovery replies.
fn find_phone(reporter: &Reporter) -> Option<String> {
    reporter.status("Searching for your phone…");
    let known = identity::phones();
    let found = proto::discover(Duration::from_millis(800));
    for f in &found {
        reporter.emit(json!({ "event": "found", "address": f.address, "model": f.model }));
    }
    let paired = found.iter().find(|f| known.iter().any(|k| k.fingerprint.starts_with(&f.fingerprint_prefix)));
    if let Some(f) = paired {
        return Some(f.address.clone());
    }
    for last in known.iter().filter(|k| !k.address.is_empty()) {
        if let Ok(addr) = last.address.parse() {
            if std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(400)).is_ok() {
                return Some(last.address.clone());
            }
        }
    }
    if let Some(f) = found.first() {
        return Some(f.address.clone());
    }
    proto::scan_subnet(proto::DEFAULT_PORT).into_iter().next()
}

fn receive(
    address: &str,
    tls: &Arc<rustls::ClientConfig>,
    me: &identity::Identity,
    tx: &SyncSender<Job>,
    skip_until_key: &AtomicBool,
    control: &Mutex<Option<proto::Sender>>,
    reporter: &Reporter,
) -> Result<(), String> {
    let mut conn = proto::Connection::open(address, tls.clone(), &identity::computer_name())?;
    let phone_fp = conn.phone_fingerprint.clone();
    let expected_code = identity::verification_code(&me.fingerprint, &phone_fp);
    let mut mismatch = false;
    let first = conn.handshake(&mut |p| match p {
        proto::Pairing::Waiting(code) => {
            // A device relaying the connection would see different certificates
            // and therefore produce a different code.
            mismatch = code != expected_code;
            reporter.emit(json!({ "event": "pairing", "code": expected_code, "mismatch": mismatch }));
        }
    })?;
    if mismatch {
        return Err("Pairing codes differ: the connection may be intercepted".into());
    }
    *control.lock().unwrap() = Some(conn.sender());
    reporter.emit(json!({ "event": "connected", "address": address, "fingerprint": phone_fp }));
    let mut pending = Some(first);
    loop {
        let message = match pending.take() {
            Some(m) => m,
            None => conn.read().map_err(|e| e.to_string())?,
        };
        match message {
            proto::Message::Hello(text) | proto::Message::State(text) => {
                if let Ok(v) = serde_json::from_str::<Value>(&text) {
                    let kind = if v.get("lenses").is_some() { "hello" } else { "state" };
                    if kind == "hello" {
                        let name = v.get("device").and_then(Value::as_str).unwrap_or("Phone").to_string();
                        identity::remember(&identity::Phone { fingerprint: phone_fp.clone(), name, address: address.to_string() });
                    }
                    reporter.emit(json!({ "event": kind, "data": v }));
                }
            }
            proto::Message::Config(c) => {
                skip_until_key.store(false, Ordering::SeqCst);
                tx.send(Job::Config(c)).map_err(|_| "decoder stopped")?;
            }
            proto::Message::Frame(f) => {
                if skip_until_key.load(Ordering::SeqCst) {
                    if !f.key {
                        continue;
                    }
                    skip_until_key.store(false, Ordering::SeqCst);
                }
                // A full queue means decoding fell behind: skip to the next key
                // frame rather than show ever older video.
                if let Err(TrySendError::Full(_)) = tx.try_send(Job::Frame(f)) {
                    skip_until_key.store(true, Ordering::SeqCst);
                }
            }
            _ => {}
        }
    }
}

fn decode_loop(rx: Receiver<Job>, shm: Arc<Mutex<shm::Shm>>, reporter: Arc<Reporter>, skip: Arc<AtomicBool>) {
    if let Err(e) = decoder::init_thread() {
        reporter.status(&e);
        return;
    }
    let mut decoder: Option<decoder::Decoder> = None;
    let mut config: Option<proto::Config> = None;
    let mut rotated = Vec::new();
    let (mut frames, mut bytes, mut decode_time) = (0u32, 0usize, Duration::ZERO);
    let mut last_report = Instant::now();
    let mut size = (0usize, 0usize);

    for job in rx {
        match job {
            Job::Config(c) => {
                decoder = None;
                match decoder::Decoder::new(c.codec, c.width, c.height, c.fps) {
                    Ok(d) => {
                        reporter.emit(json!({
                            "event": "decoder", "name": d.name,
                            "codec": format!("{:?}", c.codec), "width": c.width, "height": c.height, "fps": c.fps,
                        }));
                        decoder = Some(d);
                    }
                    Err(e) => reporter.status(&e),
                }
                config = Some(c);
            }
            Job::Lost(_) => {
                decoder = None;
                config = None;
                shm.lock().unwrap().clear();
            }
            Job::Frame(f) => {
                let (Some(d), Some(c)) = (decoder.as_mut(), config.as_ref()) else { continue };
                let started = Instant::now();
                let mut input = Vec::with_capacity(c.csd.len() + f.data.len());
                if f.key && !starts_with_parameter_sets(&f.data, c.codec) {
                    input.extend_from_slice(&c.csd);
                }
                input.extend_from_slice(&f.data);
                let t0 = Instant::now();
                match d.decode(&input, f.pts_us) {
                    Ok(pictures) => {
                        let t1 = Instant::now();
                        for p in pictures {
                            let (w, h) = nv12::rotate(&p.data, p.width as usize, p.height as usize, f.rotation, &mut rotated);
                            let t2 = Instant::now();
                            shm.lock().unwrap().publish(&rotated, w, h, c.fps, p.pts_us);
                            if std::env::var_os("PHONECAM_PROFILE").is_some() {
                                eprintln!("decode {:.1} rotate {:.1} publish {:.1}", (t1 - t0).as_secs_f64() * 1e3, (t2 - t1).as_secs_f64() * 1e3, t2.elapsed().as_secs_f64() * 1e3);
                            }
                            size = (w, h);
                            frames += 1;
                        }
                    }
                    Err(e) => {
                        reporter.status(&e);
                        skip.store(true, Ordering::SeqCst);
                    }
                }
                decode_time += started.elapsed();
                bytes += f.data.len();
            }
        }
        let elapsed = last_report.elapsed();
        if elapsed >= Duration::from_secs(1) {
            if frames > 0 {
                let fps = frames as f64 / elapsed.as_secs_f64();
                let ms = decode_time.as_secs_f64() * 1000.0 / frames as f64;
                let mbps = bytes as f64 * 8.0 / elapsed.as_secs_f64() / 1e6;
                reporter.emit(json!({
                    "event": "stats", "fps": (fps * 10.0).round() / 10.0, "decodeMs": (ms * 10.0).round() / 10.0,
                    "mbps": (mbps * 10.0).round() / 10.0, "width": size.0, "height": size.1,
                    "text": format!("LIVE - {} x {} / {fps:.1} fps / {ms:.1} ms decode / {mbps:.1} Mbps", size.0, size.1),
                }));
            }
            frames = 0;
            bytes = 0;
            decode_time = Duration::ZERO;
            last_report = Instant::now();
        }
    }
}

/// Encoders configured to repeat SPS/PPS before IDR frames already carry them.
fn starts_with_parameter_sets(au: &[u8], codec: proto::Codec) -> bool {
    let nal = au.windows(4).position(|w| w == [0, 0, 0, 1]).map(|i| i + 4)
        .or_else(|| au.windows(3).position(|w| w == [0, 0, 1]).map(|i| i + 3));
    match (nal.and_then(|i| au.get(i)), codec) {
        (Some(b), proto::Codec::H264) => b & 0x1f == 7,
        (Some(b), proto::Codec::Hevc) => (b >> 1) & 0x3f == 32,
        _ => false,
    }
}

fn run_test_pattern(shm: &Arc<Mutex<shm::Shm>>, reporter: &Reporter) -> ! {
    const W: usize = 1920;
    const H: usize = 1080;
    const FPS: u32 = 60;
    reporter.status("TEST - 1920 x 1080 / 60 fps");
    let start = Instant::now();
    let mut deadline = Instant::now();
    let mut buf = Vec::new();
    loop {
        let t = start.elapsed();
        nv12::test_pattern(&mut buf, W, H, t.as_secs_f32());
        shm.lock().unwrap().publish(&buf, W, H, FPS, t.as_micros() as i64);
        deadline += Duration::from_nanos(1_000_000_000 / FPS as u64);
        thread::sleep(deadline.saturating_duration_since(Instant::now()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn detects_parameter_sets() {
        assert!(starts_with_parameter_sets(&[0, 0, 0, 1, 0x67, 1], proto::Codec::H264));
        assert!(!starts_with_parameter_sets(&[0, 0, 0, 1, 0x65, 1], proto::Codec::H264));
        assert!(starts_with_parameter_sets(&[0, 0, 1, 0x40, 1], proto::Codec::Hevc));
    }
}
