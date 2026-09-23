//! PhoneCam wire protocol v2, client side. Mirrors
//! `android/.../net/Protocol.kt`: an 8-byte preamble from the phone, then
//! messages of `u8 type, u32 length (LE), payload` in both directions.

use std::io::{self, BufReader, Read, Write};
use std::net::{Shutdown, TcpStream, ToSocketAddrs};
use std::time::Duration;

pub const DEFAULT_PORT: u16 = 8080;
pub const DISCOVERY_PORT: u16 = 5888;
const MAX_MESSAGE: usize = 16 * 1024 * 1024;

pub const HELLO: u8 = 0x01;
pub const STATE: u8 = 0x02;
pub const CONTROL: u8 = 0x03;
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

pub struct Connection {
    reader: BufReader<TcpStream>,
    writer: TcpStream,
}

impl Connection {
    pub fn open(address: &str) -> Result<Self, String> {
        let addrs: Vec<_> = address
            .to_socket_addrs()
            .map_err(|e| e.to_string())?
            .collect();
        let stream = addrs
            .iter()
            .find_map(|a| TcpStream::connect_timeout(a, Duration::from_secs(3)).ok())
            .ok_or_else(|| "Phone unavailable. Check Wi-Fi and that PhoneCam is open.".to_string())?;
        stream.set_nodelay(true).map_err(|e| e.to_string())?;
        // Longer than one key-frame interval, shorter than a user's patience.
        stream
            .set_read_timeout(Some(Duration::from_secs(3)))
            .map_err(|e| e.to_string())?;
        let writer = stream.try_clone().map_err(|e| e.to_string())?;
        let mut reader = BufReader::with_capacity(1 << 20, stream);
        let mut preamble = [0u8; 8];
        reader.read_exact(&mut preamble).map_err(|e| e.to_string())?;
        if &preamble[..4] != b"PCAM" {
            return Err("Not a PhoneCam 2 phone (update the app)".into());
        }
        let version = u16::from_le_bytes([preamble[4], preamble[5]]);
        if version != 2 {
            return Err(format!("Unsupported protocol version {version}"));
        }
        Ok(Self { reader, writer })
    }

    /// A second handle for sending control messages from another thread.
    pub fn sender(&self) -> io::Result<Sender> {
        Ok(Sender(self.writer.try_clone()?))
    }

    pub fn read(&mut self) -> io::Result<Message> {
        let mut head = [0u8; 5];
        self.reader.read_exact(&mut head)?;
        let length = u32::from_le_bytes([head[1], head[2], head[3], head[4]]) as usize;
        if length > MAX_MESSAGE {
            return Err(invalid("message too large"));
        }
        let mut payload = vec![0u8; length];
        self.reader.read_exact(&mut payload)?;
        Ok(match head[0] {
            HELLO => Message::Hello(String::from_utf8_lossy(&payload).into_owned()),
            STATE => Message::State(String::from_utf8_lossy(&payload).into_owned()),
            CONFIG => Message::Config(parse_config(&payload)?),
            FRAME => Message::Frame(parse_frame(payload)?),
            PONG => Message::Pong(payload),
            _ => Message::Other,
        })
    }
}

impl Drop for Connection {
    fn drop(&mut self) {
        let _ = self.writer.shutdown(Shutdown::Both);
    }
}

pub struct Sender(TcpStream);

impl Sender {
    pub fn send(&mut self, kind: u8, payload: &[u8]) -> io::Result<()> {
        let mut buf = Vec::with_capacity(5 + payload.len());
        buf.push(kind);
        buf.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        buf.extend_from_slice(payload);
        self.0.write_all(&buf)
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

/// Broadcasts a discovery probe and returns `(address, model)` of every phone
/// that answers within `wait`.
pub fn discover(wait: Duration) -> Vec<(String, String)> {
    let Ok(socket) = std::net::UdpSocket::bind("0.0.0.0:0") else { return vec![] };
    let _ = socket.set_broadcast(true);
    let _ = socket.set_read_timeout(Some(Duration::from_millis(200)));
    let _ = socket.send_to(b"PHONECAM_DISCOVER_V2", ("255.255.255.255", DISCOVERY_PORT));
    let deadline = std::time::Instant::now() + wait;
    let mut found = Vec::new();
    let mut buf = [0u8; 256];
    while std::time::Instant::now() < deadline {
        if let Ok((n, from)) = socket.recv_from(&mut buf) {
            let text = String::from_utf8_lossy(&buf[..n]);
            let mut parts = text.splitn(3, ':');
            if parts.next() == Some("PHONECAM_V2") {
                let port = parts.next().and_then(|p| p.parse::<u16>().ok()).unwrap_or(DEFAULT_PORT);
                let model = parts.next().unwrap_or("Phone").to_string();
                let address = format!("{}:{port}", from.ip());
                if !found.iter().any(|(a, _)| a == &address) {
                    found.push((address, model));
                }
            }
        }
    }
    found
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
