//! PhoneCam wire protocol v2, client side. Mirrors
//! `android/.../net/Protocol.kt`: TLS 1.3 with certificates on both sides,
//! CLIENT_HELLO from the PC, an 8-byte preamble from the phone, then messages
//! of `u8 type, u32 length (LE), payload` in both directions.

use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream, ToSocketAddrs};
use std::time::Duration;

pub const DEFAULT_PORT: u16 = 8080;
pub const DISCOVERY_PORT: u16 = 5888;
const MAX_MESSAGE: usize = 16 * 1024 * 1024;

pub const HELLO: u8 = 0x01;
pub const STATE: u8 = 0x02;
pub const CONTROL: u8 = 0x03;
pub const PAIRING: u8 = 0x04;
pub const CLIENT_HELLO: u8 = 0x05;
pub const PAIR_RESULT: u8 = 0x06;
pub const CONFIG: u8 = 0x10;
pub const FRAME: u8 = 0x11;
pub const PING: u8 = 0x20;
pub const PONG: u8 = 0x21;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Codec {
    H264,
    Hevc,
}

#[derive(Clone, Debug)]
pub struct Config {
    pub codec: Codec,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub csd: Vec<u8>,
}

pub struct Frame {
    pub key: bool,
    pub pts_us: i64,
    pub rotation: u16,
    pub data: Vec<u8>,
}

pub enum Message {
    Hello(String),
    State(String),
    Config(Config),
    Frame(Frame),
    Pong(Vec<u8>),
    Other,
}

/// Accepts `host`, `host:port`, or the old `http://host:port/...` form.
pub fn parse_address(input: &str) -> Result<String, String> {
    let text = input.trim();
    let text = text.strip_prefix("http://").unwrap_or(text);
    let host = text.split(['/', '?']).next().unwrap_or("");
    if host.is_empty() || host.chars().any(|c| c.is_whitespace() || c == '@') {
        return Err("Invalid phone address".into());
    }
    Ok(if host.contains(':') {
        host.to_string()
    } else {
        format!("{host}:{DEFAULT_PORT}")
    })
}

/// Progress of a first connection to a phone that does not know this PC yet.
pub enum Pairing {
    /// The phone asked the user; the PC shows this code meanwhile.
    Waiting(String),
}

/// One TLS connection to a phone. All socket I/O happens on the thread that
/// calls [`Connection::read`]; other threads queue outgoing messages through
/// the [`Sender`], which are flushed between reads.
pub struct Connection {
    tls: rustls::ClientConnection,
    sock: TcpStream,
    buf: Vec<u8>,
    outbox: std::sync::mpsc::Receiver<(u8, Vec<u8>)>,
    outbox_tx: std::sync::mpsc::Sender<(u8, Vec<u8>)>,
    pub phone_fingerprint: String,
}

impl Connection {
    /// Connects, completes TLS and introduces the PC.
    pub fn open(address: &str, config: std::sync::Arc<rustls::ClientConfig>, pc_name: &str) -> Result<Self, String> {
        let addrs: Vec<_> = address.to_socket_addrs().map_err(|e| e.to_string())?.collect();
        let sock = addrs
            .iter()
            .find_map(|a| TcpStream::connect_timeout(a, Duration::from_secs(3)).ok())
            .ok_or_else(|| "Phone unavailable. Check Wi-Fi and that PhoneCam is open.".to_string())?;
        sock.set_nodelay(true).map_err(|e| e.to_string())?;
        sock.set_read_timeout(Some(Duration::from_secs(5))).map_err(|e| e.to_string())?;
        let name = rustls::pki_types::ServerName::try_from("phonecam.local").unwrap();
        let tls = rustls::ClientConnection::new(config, name).map_err(|e| e.to_string())?;
        let (outbox_tx, outbox) = std::sync::mpsc::channel();
        let mut conn = Self { tls, sock, buf: Vec::with_capacity(1 << 20), outbox, outbox_tx, phone_fingerprint: String::new() };
        while conn.tls.is_handshaking() {
            conn.tls.complete_io(&mut conn.sock).map_err(|e| format!("Secure connection failed: {e}"))?;
        }
        let cert = conn.tls.peer_certificates().and_then(|c| c.first()).ok_or("Phone sent no certificate")?;
        conn.phone_fingerprint = crate::identity::fingerprint(cert.as_ref());

        conn.write_now(CLIENT_HELLO, serde_json::json!({ "name": pc_name }).to_string().as_bytes())
            .map_err(|e| e.to_string())?;
        conn.fill(8).map_err(|e| e.to_string())?;
        let preamble: Vec<u8> = conn.buf.drain(..8).collect();
        if &preamble[..4] != b"PCAM" {
            return Err("Not a PhoneCam 2 phone (update the app)".into());
        }
        let version = u16::from_le_bytes([preamble[4], preamble[5]]);
        if version != 2 {
            return Err(format!("Unsupported protocol version {version}"));
        }
        Ok(conn)
    }

    /// Handles the pairing exchange if the phone starts one. Call once, right
    /// after [`Connection::open`]; returns the first regular message.
    pub fn handshake(&mut self, on_pairing: &mut dyn FnMut(Pairing)) -> Result<Message, String> {
        let first = self.read_raw().map_err(|e| e.to_string())?;
        if first.0 != PAIRING {
            return Self::decode(first).map_err(|e| e.to_string());
        }
        let v: serde_json::Value = serde_json::from_slice(&first.1).unwrap_or_default();
        on_pairing(Pairing::Waiting(v.get("code").and_then(|c| c.as_str()).unwrap_or("------").to_string()));
        // The user has up to 90 seconds to answer on the phone.
        self.sock.set_read_timeout(Some(Duration::from_secs(95))).map_err(|e| e.to_string())?;
        let answer = self.read_raw().map_err(|e| e.to_string())?;
        self.sock.set_read_timeout(Some(Duration::from_secs(5))).map_err(|e| e.to_string())?;
        let ok = answer.0 == PAIR_RESULT
            && serde_json::from_slice::<serde_json::Value>(&answer.1).ok().and_then(|v| v.get("ok")?.as_bool()) == Some(true);
        if !ok {
            return Err("Connection was declined on the phone".into());
        }
        let next = self.read_raw().map_err(|e| e.to_string())?;
        Self::decode(next).map_err(|e| e.to_string())
    }

    pub fn sender(&self) -> Sender {
        Sender(self.outbox_tx.clone())
    }

    pub fn read(&mut self) -> io::Result<Message> {
        let raw = self.read_raw()?;
        Self::decode(raw)
    }

    fn decode((kind, payload): (u8, Vec<u8>)) -> io::Result<Message> {
        Ok(match kind {
            HELLO => Message::Hello(String::from_utf8_lossy(&payload).into_owned()),
            STATE => Message::State(String::from_utf8_lossy(&payload).into_owned()),
            CONFIG => Message::Config(parse_config(&payload)?),
            FRAME => Message::Frame(parse_frame(payload)?),
            PONG => Message::Pong(payload),
            _ => Message::Other,
        })
    }

    fn read_raw(&mut self) -> io::Result<(u8, Vec<u8>)> {
        self.fill(5)?;
        let length = u32::from_le_bytes([self.buf[1], self.buf[2], self.buf[3], self.buf[4]]) as usize;
        if length > MAX_MESSAGE {
            return Err(invalid("message too large"));
        }
        self.fill(5 + length)?;
        let kind = self.buf[0];
        let payload = self.buf[5..5 + length].to_vec();
        self.buf.drain(..5 + length);
        Ok((kind, payload))
    }

    /// Reads until `buf` holds at least `n` plaintext bytes, sending queued
    /// outgoing messages in between.
    fn fill(&mut self, n: usize) -> io::Result<()> {
        let mut chunk = [0u8; 64 * 1024];
        while self.buf.len() < n {
            while let Ok((kind, payload)) = self.outbox.try_recv() {
                self.write_now(kind, &payload)?;
            }
            match self.tls.reader().read(&mut chunk) {
                Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
                Ok(k) => {
                    self.buf.extend_from_slice(&chunk[..k]);
                    continue;
                }
                Err(e) if e.kind() == io::ErrorKind::WouldBlock => {}
                Err(e) => return Err(e),
            }
            match self.tls.read_tls(&mut self.sock) {
                Ok(0) => return Err(io::ErrorKind::UnexpectedEof.into()),
                Ok(_) => {
                    self.tls.process_new_packets().map_err(|e| io::Error::new(io::ErrorKind::InvalidData, e))?;
                }
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {
                    return Err(io::Error::new(io::ErrorKind::TimedOut, "Phone stopped responding"));
                }
                Err(e) => return Err(e),
            }
        }
        Ok(())
    }

    fn write_now(&mut self, kind: u8, payload: &[u8]) -> io::Result<()> {
        let mut msg = Vec::with_capacity(5 + payload.len());
        msg.push(kind);
        msg.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        msg.extend_from_slice(payload);
        self.tls.writer().write_all(&msg)?;
        while self.tls.wants_write() {
            self.tls.write_tls(&mut self.sock)?;
        }
        Ok(())
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        self.tls.send_close_notify();
        let _ = self.tls.write_tls(&mut self.sock);
        let _ = self.sock.shutdown(Shutdown::Both);
    }
}

/// Queues messages for the connection's I/O thread.
#[derive(Clone)]
pub struct Sender(std::sync::mpsc::Sender<(u8, Vec<u8>)>);

impl Sender {
    pub fn send(&self, kind: u8, payload: &[u8]) -> bool {
        self.0.send((kind, payload.to_vec())).is_ok()
    }
}

fn parse_config(p: &[u8]) -> io::Result<Config> {
    if p.len() < 7 {
        return Err(invalid("short CONFIG"));
    }
    let codec = match p[0] {
        1 => Codec::H264,
        2 => Codec::Hevc,
        other => return Err(invalid(&format!("unknown codec {other}"))),
    };
    let width = u16::from_le_bytes([p[1], p[2]]) as u32;
    let height = u16::from_le_bytes([p[3], p[4]]) as u32;
    let fps = u16::from_le_bytes([p[5], p[6]]) as u32;
    if width == 0 || height == 0 || width > 3840 || height > 3840 || fps == 0 {
        return Err(invalid("bad CONFIG dimensions"));
    }
    Ok(Config { codec, width, height, fps, csd: p[7..].to_vec() })
}

fn parse_frame(mut p: Vec<u8>) -> io::Result<Frame> {
    if p.len() < 11 {
        return Err(invalid("short FRAME"));
    }
    let key = p[0] & 1 != 0;
    let pts_us = i64::from_le_bytes(p[1..9].try_into().unwrap());
    let rotation = u16::from_le_bytes([p[9], p[10]]);
    if !matches!(rotation, 0 | 90 | 180 | 270) {
        return Err(invalid("bad rotation"));
    }
    p.drain(..11);
    Ok(Frame { key, pts_us, rotation, data: p })
}

fn invalid(s: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, s.to_string())
}

/// A phone answering discovery: address, first 16 hex digits of its
/// certificate fingerprint, and model name.
#[derive(Clone, Debug)]
pub struct Found {
    pub address: String,
    pub fingerprint_prefix: String,
    pub model: String,
}

/// Broadcasts a discovery probe and returns every phone that answers within `wait`.
pub fn discover(wait: Duration) -> Vec<Found> {
    let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") else { return vec![] };
    let _ = socket.set_broadcast(true);
    let _ = socket.set_read_timeout(Some(Duration::from_millis(200)));
    let _ = socket.send_to(b"PHONECAM_DISCOVER_V2", ("255.255.255.255", DISCOVERY_PORT));
    if let Some(ip) = local_ipv4() {
        let [a, b, c, _] = ip.octets();
        let _ = socket.send_to(b"PHONECAM_DISCOVER_V2", (std::net::Ipv4Addr::new(a, b, c, 255), DISCOVERY_PORT));
    }
    let deadline = std::time::Instant::now() + wait;
    let mut found = Vec::new();
    let mut buf = [0u8; 256];
    while std::time::Instant::now() < deadline {
        if let Ok((n, from)) = socket.recv_from(&mut buf) {
            let text = String::from_utf8_lossy(&buf[..n]);
            let mut parts = text.splitn(4, ':');
            if parts.next() == Some("PHONECAM_V2") {
                let port = parts.next().and_then(|p| p.parse::<u16>().ok()).unwrap_or(DEFAULT_PORT);
                let fingerprint_prefix = parts.next().unwrap_or_default().to_string();
                let model = parts.next().unwrap_or("Phone").to_string();
                let address = format!("{}:{port}", from.ip());
                if !found.iter().any(|f: &Found| f.address == address) {
                    found.push(Found { address, fingerprint_prefix, model });
                }
            }
        }
    }
    found
}

/// The PC's own IPv4 address on the network that leads to the internet
/// (nothing is actually sent: connecting a UDP socket only picks a route).
pub fn local_ipv4() -> Option<std::net::Ipv4Addr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    match socket.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(ip) if !ip.is_loopback() && !ip.is_unspecified() => Some(ip),
        _ => None,
    }
}

/// Finds every host in the PC's /24 that accepts TCP on `port`.
///
/// Discovery by UDP broadcast is blocked by the Windows firewall on many PCs
/// (the reply comes from an address the PC never sent to), while outgoing TCP
/// connections are always allowed. 254 parallel connection attempts finish in
/// well under a second on a home network.
pub fn scan_subnet(port: u16) -> Vec<String> {
    let Some(ip) = local_ipv4() else { return vec![] };
    let [a, b, c, own] = ip.octets();
    let hosts: Vec<u8> = (1..=254u8).filter(|h| *h != own).collect();
    let found = std::sync::Mutex::new(Vec::new());
    std::thread::scope(|scope| {
        for chunk in hosts.chunks(1) {
            let found = &found;
            scope.spawn(move || {
                for h in chunk {
                    let addr = std::net::SocketAddr::from(([a, b, c, *h], port));
                    if TcpStream::connect_timeout(&addr, Duration::from_millis(350)).is_ok() {
                        found.lock().unwrap().push(addr.to_string());
                    }
                }
            });
        }
    });
    found.into_inner().unwrap()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses() {
        assert_eq!(parse_address("192.168.1.5").unwrap(), "192.168.1.5:8080");
        assert_eq!(parse_address("192.168.1.5:9000").unwrap(), "192.168.1.5:9000");
        assert_eq!(parse_address("http://10.0.0.2:8080/stream?code=1").unwrap(), "10.0.0.2:8080");
        assert!(parse_address(" ").is_err());
    }

    #[test]
    fn frame_header() {
        let mut p = vec![1u8];
        p.extend_from_slice(&123456i64.to_le_bytes());
        p.extend_from_slice(&90u16.to_le_bytes());
        p.extend_from_slice(&[0, 0, 0, 1, 0x65]);
        let f = parse_frame(p).unwrap();
        assert!(f.key);
        assert_eq!((f.pts_us, f.rotation), (123456, 90));
        assert_eq!(f.data, vec![0, 0, 0, 1, 0x65]);
    }

    #[test]
    fn rejects_bad_config() {
        assert!(parse_config(&[1, 0, 0, 0, 0, 30, 0]).is_err());
        assert!(parse_config(&[9, 0x80, 7, 0x38, 4, 30, 0]).is_err());
        let c = parse_config(&[1, 0x80, 7, 0x38, 4, 60, 0, 0, 0, 0, 1]).unwrap();
        assert_eq!((c.width, c.height, c.fps, c.csd.len()), (1920, 1080, 60, 4));
    }
}
