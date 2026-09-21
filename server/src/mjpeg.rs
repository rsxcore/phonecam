use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

pub const MAX_JPEG: usize = 8 * 1024 * 1024;
#[derive(Debug, Clone)]
pub struct Endpoint {
    pub authority: String,
    pub path: String,
}
impl Endpoint {
    pub fn parse(input: &str) -> Result<Self, String> {
        let text = input.trim();
        if text.is_empty() || text.chars().any(|c| c.is_control() || c == '"' || c == ' ') {
            return Err("Invalid phone address".into());
        }
        if text.contains("://") && !text.starts_with("http://") {
            return Err("Use http:// on your local network".into());
        }
        let text = text.strip_prefix("http://").unwrap_or(text);
        let (host, tail) = text.split_once('/').unwrap_or((text, ""));
        if host.is_empty() || host.contains('@') || host.contains('?') {
            return Err("Invalid host".into());
        }
        let authority = if host.contains(':') {
            host.to_string()
        } else {
            format!("{host}:8080")
        };
        let path = if tail.is_empty() {
            "/stream".into()
        } else if tail.starts_with('?') {
            format!("/stream{tail}")
        } else {
            format!("/{tail}")
        };
        Ok(Self { authority, path })
    }
    pub fn connect(&self) -> Result<Multipart<BufReader<TcpStream>>, String> {
        let addresses: Vec<_> = self
            .authority
            .to_socket_addrs()
            .map_err(|e| e.to_string())?
            .collect();
        let mut stream = addresses
            .iter()
            .find_map(|a| TcpStream::connect_timeout(a, Duration::from_secs(3)).ok())
            .ok_or_else(|| {
                "Phone unavailable. Check Wi-Fi, address and Start on phone.".to_string()
            })?;
        stream
            .set_read_timeout(Some(Duration::from_secs(4)))
            .map_err(|e| e.to_string())?;
        stream
            .set_write_timeout(Some(Duration::from_secs(3)))
            .map_err(|e| e.to_string())?;
        stream.set_nodelay(true).map_err(|e| e.to_string())?;
        write!(
            stream,
            "GET {} HTTP/1.1\r\nHost: {}\r\nConnection: close\r\n\r\n",
            self.path, self.authority
        )
        .map_err(|e| e.to_string())?;
        Multipart::open(BufReader::with_capacity(64 * 1024, stream)).map_err(|e| e.to_string())
    }
}
fn invalid(s: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, s)
}
fn line<R: BufRead>(r: &mut R) -> io::Result<String> {
    let mut b = Vec::new();
    r.take(4097).read_until(b'\n', &mut b)?;
    if b.is_empty() {
        return Err(io::ErrorKind::UnexpectedEof.into());
    }
    if b.len() > 4096 || !b.ends_with(b"\n") {
        return Err(invalid("HTTP line too long"));
    }
    String::from_utf8(b)
        .map(|s| s.trim_end_matches(['\r', '\n']).to_string())
        .map_err(|_| invalid("Invalid HTTP header"))
}
fn headers<R: BufRead>(r: &mut R) -> io::Result<Vec<(String, String)>> {
    let mut result = Vec::new();
    for _ in 0..64 {
        let s = line(r)?;
        if s.is_empty() {
            return Ok(result);
        }
        let (k, v) = s
            .split_once(':')
            .ok_or_else(|| invalid("Malformed header"))?;
        result.push((k.trim().to_ascii_lowercase(), v.trim().to_string()));
    }
    Err(invalid("Too many headers"))
}
fn get<'a>(h: &'a [(String, String)], key: &str) -> Option<&'a str> {
    h.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
}
fn rotation(s: Option<&str>, default: u16) -> io::Result<u16> {
    let n = match s {
        Some(v) => v.parse().map_err(|_| invalid("Bad rotation"))?,
        None => default,
    };
    if [0, 90, 180, 270].contains(&n) {
        Ok(n)
    } else {
        Err(invalid("Bad rotation"))
    }
}
pub struct Packet {
    pub jpeg: Vec<u8>,
    pub rotation: u16,
}
pub struct Multipart<R> {
    reader: R,
    boundary: String,
    rotation: u16,
}
impl<R: BufRead> Multipart<R> {
    pub fn open(mut reader: R) -> io::Result<Self> {
        let status = line(&mut reader)?;
        if status.split_whitespace().nth(1) != Some("200") {
            return Err(invalid(&format!(
                "Phone returned {status}; check the connection code"
            )));
        }
        let h = headers(&mut reader)?;
        if get(&h, "transfer-encoding").is_some() {
            return Err(invalid("Chunked streams are not supported"));
        }
        let content = get(&h, "content-type").ok_or_else(|| invalid("Missing Content-Type"))?;
        if !content
            .to_ascii_lowercase()
            .starts_with("multipart/x-mixed-replace")
        {
            return Err(invalid("Address is not a PhoneCam stream"));
        }
        let boundary = get(&h, "x-phonecam-boundary")
            .map(str::to_string)
            .or_else(|| {
                content.split(';').find_map(|s| {
                    let (k, v) = s.trim().split_once('=')?;
                    k.eq_ignore_ascii_case("boundary")
                        .then(|| v.trim_matches('"').to_string())
                })
            })
            .ok_or_else(|| invalid("Missing boundary"))?;
        if boundary.is_empty() || boundary.len() > 100 || boundary.chars().any(char::is_control) {
            return Err(invalid("Invalid boundary"));
        }
        Ok(Self {
            reader,
            boundary: format!("--{boundary}"),
            rotation: rotation(get(&h, "x-phonecam-rotation"), 0)?,
        })
    }
    pub fn next(&mut self) -> io::Result<Packet> {
        let mut boundary = line(&mut self.reader)?;
        if boundary.is_empty() {
            boundary = line(&mut self.reader)?;
        }
        if boundary == format!("{}--", self.boundary) {
            return Err(io::ErrorKind::UnexpectedEof.into());
        }
        if boundary != self.boundary {
            return Err(invalid("Invalid frame boundary"));
        }
        let h = headers(&mut self.reader)?;
        let size: usize = get(&h, "content-length")
            .ok_or_else(|| invalid("Missing frame length"))?
            .parse()
            .map_err(|_| invalid("Invalid frame length"))?;
        if !(4..=MAX_JPEG).contains(&size) {
            return Err(invalid("Frame size outside limit"));
        }
        let mut jpeg = vec![0; size];
        self.reader.read_exact(&mut jpeg)?;
        if !jpeg.starts_with(&[255, 216]) || !jpeg.ends_with(&[255, 217]) {
            return Err(invalid("Truncated JPEG"));
        }
        Ok(Packet {
            jpeg,
            rotation: rotation(get(&h, "x-phonecam-rotation"), self.rotation)?,
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    fn stream(extra: &str, parts: &str) -> Vec<u8> {
        format!("HTTP/1.1 200 OK\r\nContent-Type: multipart/x-mixed-replace; boundary=\"test\"\r\n{extra}\r\n{parts}").into_bytes()
    }
    #[test]
    fn endpoint_validation() {
        assert_eq!(
            Endpoint::parse("192.168.1.2").unwrap().authority,
            "192.168.1.2:8080"
        );
        assert_eq!(
            Endpoint::parse("http://phone:8080/?code=123456")
                .unwrap()
                .path,
            "/stream?code=123456"
        );
        for s in ["", "a\r\nb", "https://a", "a b", "a@b"] {
            assert!(Endpoint::parse(s).is_err());
        }
    }
    #[test]
    fn rotation_per_frame_and_fragmented_reads() {
        let mut data = stream(
            "X-PhoneCam-Rotation: 90\r\n",
            "--test\r\nContent-Length: 4\r\nX-PhoneCam-Rotation: 270\r\n\r\n",
        );
        data.extend([255, 216, 255, 217]);
        data.extend(b"\r\n--test--\r\n");
        let mut p = Multipart::open(BufReader::with_capacity(1, &data[..])).unwrap();
        assert_eq!(p.next().unwrap().rotation, 270);
        assert!(p.next().is_err());
    }
    #[test]
    fn hostile_lengths_and_headers() {
        for len in ["-1", "0", "8388609", "999999999999999999999999"] {
            let d = stream("", &format!("--test\r\nContent-Length: {len}\r\n\r\n"));
            assert!(Multipart::open(&d[..]).unwrap().next().is_err());
        }
        assert!(Multipart::open(&stream("Transfer-Encoding: chunked\r\n", "")[..]).is_err());
        assert!(Multipart::open(&vec![b'x'; 5000][..]).is_err());
    }
}
