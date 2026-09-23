//! The receiver: finds the phone, keeps one secure connection alive, decodes
//! video and publishes it to the virtual camera. Used by both the desktop app
//! and the `phonecam-server` command-line tool.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::{decoder, identity, nv12, proto, shm};

/// Everything the engine reports, as JSON objects with an `event` field.
pub type EventSink = Arc<dyn Fn(Value) + Send + Sync>;

/// Encoded video for an in-app preview, forwarded untouched from the phone.
#[derive(Clone)]
pub enum VideoPacket {
    Config { codec: proto::Codec, width: u32, height: u32, fps: u32 },
    Frame { key: bool, pts_us: i64, rotation: u16, data: Arc<Vec<u8>> },
}
pub type VideoSink = Arc<dyn Fn(VideoPacket) + Send + Sync>;

/// Close the current connection so the engine reconnects (to a new target).
const DISCONNECT: u8 = 0xFF;

enum Job {
    Config(proto::Config),
    Frame(proto::Frame),
    Lost,
}

struct Shared {
    events: EventSink,
    video: Option<VideoSink>,
    preview: AtomicBool,
    test_pattern: AtomicBool,
    control: Mutex<Option<proto::Sender>>,
    target: Mutex<Option<String>>,
    shm: Mutex<shm::Shm>,
}

impl Shared {
    fn emit(&self, v: Value) {
        (self.events)(v);
    }
    fn status(&self, text: &str) {
        self.emit(json!({ "event": "status", "text": text }));
    }
}

#[derive(Clone)]
pub struct Engine {
    shared: Arc<Shared>,
}

impl Engine {
    /// Starts the receiver threads. `address` pins a phone; `None` means find it.
    ///
    /// Touches no COM state on the calling thread, so it is safe to call from a
    /// UI thread that must stay single-threaded (WebView2 requires that).
    pub fn start(address: Option<String>, events: EventSink, video: Option<VideoSink>) -> Result<Self, String> {
        let shm = shm::Shm::create()?;
        let identity = identity::Identity::load_or_create().map_err(|e| format!("Cannot create the PC identity: {e}"))?;
        let tls = identity.client_config()?;
        let shared = Arc::new(Shared {
            events,
            video,
            preview: AtomicBool::new(false),
            test_pattern: AtomicBool::new(false),
            control: Mutex::new(None),
            target: Mutex::new(address),
            shm: Mutex::new(shm),
        });
        shared.emit(json!({ "event": "identity", "name": identity::computer_name(), "fingerprint": identity.fingerprint }));

        let (tx, rx) = mpsc::sync_channel::<Job>(16);
        let skip = Arc::new(AtomicBool::new(false));
        {
            let (shared, skip) = (shared.clone(), skip.clone());
            thread::Builder::new().name("phonecam-decode".into()).spawn(move || decode_loop(rx, shared, skip)).unwrap();
        }
        {
            let shared = shared.clone();
            thread::Builder::new().name("phonecam-test".into()).spawn(move || test_pattern_loop(shared)).unwrap();
        }
        {
            let shared = shared.clone();
            thread::Builder::new()
                .name("phonecam-net".into())
                .spawn(move || connection_loop(shared, identity, tls, tx, skip))
                .unwrap();
        }
        Ok(Self { shared })
    }

    /// Forwards a JSON control message (`{"set": {...}}`) to the phone.
    pub fn send_control(&self, json: &str) -> bool {
        match self.shared.control.lock().unwrap().as_ref() {
            Some(sender) => sender.send(proto::CONTROL, json.as_bytes()),
            None => false,
        }
    }

    /// Switches to another phone address, or back to automatic search.
    pub fn connect_to(&self, address: Option<String>) {
        *self.shared.target.lock().unwrap() = address;
        if let Some(sender) = self.shared.control.lock().unwrap().as_ref() {
            sender.send(DISCONNECT, &[]);
        }
    }

    /// Streams encoded video to the video sink while enabled (the app window is visible).
    pub fn set_preview(&self, on: bool) {
        self.shared.preview.store(on, Ordering::SeqCst);
    }

    /// Shows moving colour bars in the virtual camera instead of the phone.
    pub fn set_test_pattern(&self, on: bool) {
        self.shared.test_pattern.store(on, Ordering::SeqCst);
        if !on {
            self.shared.shm.lock().unwrap().clear();
        }
    }
}

fn connection_loop(
    shared: Arc<Shared>,
    me: identity::Identity,
    tls: Arc<rustls::ClientConfig>,
    tx: SyncSender<Job>,
    skip: Arc<AtomicBool>,
) {
    loop {
        let pinned = shared.target.lock().unwrap().clone();
        let target = match pinned {
            Some(a) => Some(a),
            None => find_phone(&shared),
        };
        let result = match target {
            Some(t) => {
                shared.emit(json!({ "event": "connecting", "address": t }));
                shared.status(&format!("Connecting to {t}…"));
                receive(&t, &shared, &tls, &me, &tx, &skip)
            }
            None => Err("Phone not found. Open PhoneCam on the phone (same Wi-Fi).".into()),
        };
        *shared.control.lock().unwrap() = None;
        let error = result.err().unwrap_or_else(|| "Disconnected".into());
        let _ = tx.send(Job::Lost);
        shared.emit(json!({ "event": "disconnected", "reason": error }));
        shared.status(&format!("Reconnecting: {error}"));
        thread::sleep(Duration::from_millis(800));
    }
}

/// Paired phones first (by the fingerprint prefix they announce), then the
/// addresses that worked before, then any phone that answers, then a scan of
/// the local network for when the firewall swallows discovery replies.
fn find_phone(shared: &Shared) -> Option<String> {
    shared.emit(json!({ "event": "searching" }));
    shared.status("Searching for your phone…");
    let known = identity::phones();
    let found = proto::discover(Duration::from_millis(800));
    for f in &found {
        shared.emit(json!({ "event": "found", "address": f.address, "model": f.model }));
    }
    if let Some(f) = found.iter().find(|f| known.iter().any(|k| k.fingerprint.starts_with(&f.fingerprint_prefix))) {
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
    shared: &Shared,
    tls: &Arc<rustls::ClientConfig>,
    me: &identity::Identity,
    tx: &SyncSender<Job>,
    skip: &AtomicBool,
) -> Result<(), String> {
    let mut conn = proto::Connection::open(address, tls.clone(), &identity::computer_name())?;
    let phone_fp = conn.phone_fingerprint.clone();
    let expected = identity::verification_code(&me.fingerprint, &phone_fp);
    let mut mismatch = false;
    let first = conn.handshake(&mut |p| match p {
        proto::Pairing::Waiting(code) => {
            // A device relaying the connection sees different certificates and
            // therefore produces a different code.
            mismatch = code != expected;
            shared.emit(json!({ "event": "pairing", "code": expected, "mismatch": mismatch }));
        }
    })?;
    if mismatch {
        return Err("Pairing codes differ: the connection may be intercepted".into());
    }
    *shared.control.lock().unwrap() = Some(conn.sender());
    shared.emit(json!({ "event": "connected", "address": address, "fingerprint": phone_fp }));
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
                    shared.emit(json!({ "event": kind, "data": v }));
                }
            }
            proto::Message::Config(c) => {
                skip.store(false, Ordering::SeqCst);
                if let Some(video) = &shared.video {
                    video(VideoPacket::Config { codec: c.codec, width: c.width, height: c.height, fps: c.fps });
                }
                tx.send(Job::Config(c)).map_err(|_| "decoder stopped")?;
            }
            proto::Message::Frame(f) => {
                if skip.load(Ordering::SeqCst) {
                    if !f.key {
                        continue;
                    }
                    skip.store(false, Ordering::SeqCst);
                }
                if let (Some(video), true) = (&shared.video, shared.preview.load(Ordering::Relaxed)) {
                    video(VideoPacket::Frame { key: f.key, pts_us: f.pts_us, rotation: f.rotation, data: Arc::new(f.data.clone()) });
                }
                // A full queue means decoding fell behind: skip to the next key
                // frame rather than show ever older video.
                if let Err(TrySendError::Full(_)) = tx.try_send(Job::Frame(f)) {
                    skip.store(true, Ordering::SeqCst);
                }
            }
            _ => {}
        }
    }
}

fn decode_loop(rx: Receiver<Job>, shared: Arc<Shared>, skip: Arc<AtomicBool>) {
    if let Err(e) = decoder::init_thread() {
        shared.emit(json!({ "event": "fatal", "text": e }));
        return;
    }
    shared.emit(json!({
        "event": "codecs",
        "h264": decoder::supports(proto::Codec::H264),
        "hevc": decoder::supports(proto::Codec::Hevc),
    }));
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
                        shared.emit(json!({
                            "event": "decoder", "name": d.name,
                            "codec": format!("{:?}", c.codec), "width": c.width, "height": c.height, "fps": c.fps,
                        }));
                        decoder = Some(d);
                    }
                    Err(e) => shared.status(&e),
                }
                config = Some(c);
            }
            Job::Lost => {
                decoder = None;
                config = None;
                if !shared.test_pattern.load(Ordering::SeqCst) {
                    shared.shm.lock().unwrap().clear();
                }
            }
            Job::Frame(f) => {
                let (Some(d), Some(c)) = (decoder.as_mut(), config.as_ref()) else { continue };
                let started = Instant::now();
                let mut input = Vec::with_capacity(c.csd.len() + f.data.len());
                if f.key && !starts_with_parameter_sets(&f.data, c.codec) {
                    input.extend_from_slice(&c.csd);
                }
                input.extend_from_slice(&f.data);
                match d.decode(&input, f.pts_us) {
                    Ok(pictures) => {
                        for p in pictures {
                            let (w, h) = nv12::rotate(&p.data, p.width as usize, p.height as usize, f.rotation, &mut rotated);
                            if !shared.test_pattern.load(Ordering::Relaxed) {
                                shared.shm.lock().unwrap().publish(&rotated, w, h, c.fps, p.pts_us);
                            }
                            size = (w, h);
                            frames += 1;
                        }
                    }
                    Err(e) => {
                        shared.status(&e);
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
                shared.emit(json!({
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

fn test_pattern_loop(shared: Arc<Shared>) {
    const W: usize = 1920;
    const H: usize = 1080;
    const FPS: u32 = 60;
    let start = Instant::now();
    let mut buf = Vec::new();
    loop {
        if !shared.test_pattern.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(100));
            continue;
        }
        let mut deadline = Instant::now();
        while shared.test_pattern.load(Ordering::Relaxed) {
            let t = start.elapsed();
            nv12::test_pattern(&mut buf, W, H, t.as_secs_f32());
            shared.shm.lock().unwrap().publish(&buf, W, H, FPS, t.as_micros() as i64);
            deadline += Duration::from_nanos(1_000_000_000 / FPS as u64);
            thread::sleep(deadline.saturating_duration_since(Instant::now()));
        }
    }
}

/// Encoders configured to repeat SPS/PPS before IDR frames already carry them.
fn starts_with_parameter_sets(au: &[u8], codec: proto::Codec) -> bool {
    let nal = au
        .windows(4)
        .position(|w| w == [0, 0, 0, 1])
        .map(|i| i + 4)
        .or_else(|| au.windows(3).position(|w| w == [0, 0, 1]).map(|i| i + 3));
    match (nal.and_then(|i| au.get(i)), codec) {
        (Some(b), proto::Codec::H264) => b & 0x1f == 7,
        (Some(b), proto::Codec::Hevc) => (b >> 1) & 0x3f == 32,
        _ => false,
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
