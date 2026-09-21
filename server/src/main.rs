mod mjpeg;
mod video;
use std::mem::{offset_of, size_of};
use std::sync::atomic::{AtomicU32, Ordering};
use std::thread;
use std::time::{Duration, Instant};

use windows::core::PCWSTR;
use windows::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE};
use windows::Win32::System::Memory::{
    CreateFileMappingW, MapViewOfFile, UnmapViewOfFile, FILE_MAP_ALL_ACCESS,
    MEMORY_MAPPED_VIEW_ADDRESS, PAGE_READWRITE,
};

// ---------------------------------------------------------------------------
// Layout. Mirrors PhoneCamProtocol.h; the assertions below fail the build if
// the two ever drift apart.
// ---------------------------------------------------------------------------

const SHM_NAME: &str = r"Local\PhoneCam_Frame_v1";
const MAGIC: u32 = 0x4D41_4350; // 'PCAM'
const VERSION: u32 = 1;
const FMT_BGRA: u32 = 1;
const PIXELS_OFFSET: usize = 64;
const MAX_W: u32 = 1920;
const MAX_H: u32 = 1080;

/// One for the filter to read, one for us to draw into.
const BUFFERS: u32 = 2;
const BUF_BYTES: usize = (MAX_W as usize) * (MAX_H as usize) * 4;
const SHM_SIZE: usize = PIXELS_OFFSET + (BUFFERS as usize) * BUF_BYTES;

fn buf_offset(n: u32) -> usize {
    PIXELS_OFFSET + (n as usize) * BUF_BYTES
}

#[repr(C)]
struct Header {
    magic: u32,
    version: u32,
    width: u32,
    height: u32,
    stride: u32,
    format: u32,
    /// Seqlock counter. Odd means a draw is in progress; even means the buffer
    /// named by `active_buffer` is whole and safe to read.
    frame_index: u32,
    active_buffer: u32,
    reserved: [u32; 8],
}

const _: () = assert!(size_of::<Header>() == PIXELS_OFFSET);
const _: () = assert!(offset_of!(Header, frame_index) == 24);
const _: () = assert!(offset_of!(Header, active_buffer) == 28);

/// What the filter negotiates by default. The phone will match this.
const WIDTH: u32 = 1280;
const HEIGHT: u32 = 720;
const FPS: u32 = 30;

// ---------------------------------------------------------------------------
// Shared memory
// ---------------------------------------------------------------------------

struct Shm {
    mapping: HANDLE,
    view: MEMORY_MAPPED_VIEW_ADDRESS,
    mutex: HANDLE,
}

impl Shm {
    fn create() -> Result<Self, String> {
        let name: Vec<u16> = SHM_NAME.encode_utf16().chain(std::iter::once(0)).collect();

        unsafe {
            let mutex_name: Vec<u16> = r"Local\PhoneCam_Writer_v1"
                .encode_utf16()
                .chain(Some(0))
                .collect();
            let mutex = windows::Win32::System::Threading::CreateMutexW(
                None,
                false,
                PCWSTR(mutex_name.as_ptr()),
            )
            .map_err(|e| e.to_string())?;
            if GetLastError() == ERROR_ALREADY_EXISTS {
                let _ = CloseHandle(mutex);
                return Err("PhoneCam is already connected in another window".into());
            }
            let mapping = CreateFileMappingW(
                windows::Win32::Foundation::INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                (SHM_SIZE >> 32) as u32,
                (SHM_SIZE & 0xFFFF_FFFF) as u32,
                PCWSTR(name.as_ptr()),
            )
            .map_err(|e| format!("CreateFileMappingW failed: {e}"))?;

            let view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, SHM_SIZE);
            if view.Value.is_null() {
                let _ = CloseHandle(mapping);
                return Err(format!(
                    "MapViewOfFile failed: {}",
                    windows::core::Error::from_win32()
                ));
            }

            Ok(Self {
                mapping,
                view,
                mutex,
            })
        }
    }

    fn base(&self) -> *mut u8 {
        self.view.Value as *mut u8
    }

    fn header(&mut self) -> &mut Header {
        unsafe { &mut *(self.base() as *mut Header) }
    }

    /// Handed to the reader as a plain atomic so the release-store below is a
    /// real fence and not something the optimiser may reorder.
    fn frame_index(&self) -> &AtomicU32 {
        unsafe { &*(self.base().add(offset_of!(Header, frame_index)) as *const AtomicU32) }
    }

    /// Published last: the reader trusts this only once frame_index is even.
    fn active_buffer(&self) -> &AtomicU32 {
        unsafe { &*(self.base().add(offset_of!(Header, active_buffer)) as *const AtomicU32) }
    }

    fn pixels(&self, n: u32) -> *mut u8 {
        unsafe { self.base().add(buf_offset(n)) }
    }
}

impl Drop for Shm {
    fn drop(&mut self) {
        unsafe {
            self.frame_index().store(0, Ordering::SeqCst);
            let _ = UnmapViewOfFile(self.view);
            let _ = CloseHandle(self.mapping);
            let _ = CloseHandle(self.mutex);
        }
    }
}

// ---------------------------------------------------------------------------
// Test pattern
// ---------------------------------------------------------------------------

/// Colour bars, a band that sweeps sideways, a ring, and a band that drifts up
/// and down. Between them they answer the four questions worth asking of a
/// virtual camera: are the colours right, is it live, is the aspect ratio
/// square, and is it the right way up.
fn draw(buf: &mut [u8], phase: f32) {
    // Written as 0x00RRGGBB, but the bytes below are stored B,G,R,A — so the
    // hex reads backwards from the colour it names. Anchoring on the bytes
    // rather than the hex is what keeps these labels honest.
    const BARS: [u32; 8] = [
        0x00FF_FFFF, // white   B=FF G=FF R=FF
        0x0000_FFFF, // cyan    B=FF G=FF R=00
        0x00FF_FF00, // yellow  B=00 G=FF R=FF
        0x0000_FF00, // green   B=00 G=FF R=00
        0x00FF_00FF, // magenta B=FF G=00 R=FF
        0x0000_00FF, // blue    B=FF G=00 R=00
        0x00FF_0000, // red     B=00 G=00 R=FF
        0x0000_0000, // black
    ];

    let w = WIDTH as usize;
    let h = HEIGHT as usize;
    let stride = w * 4;

    // Every row of the bars is identical, so build one row and blit it.
    let mut row = vec![0u8; stride];
    let sweep = ((phase * w as f32) as usize) % w;
    for x in 0..w {
        let bar = (x * BARS.len() / w).min(BARS.len() - 1);
        let mut px = BARS[bar];
        if x.abs_diff(sweep) < 40 {
            px = 0x00FF_FFFF;
        }
        row[x * 4] = (px & 0xFF) as u8;
        row[x * 4 + 1] = ((px >> 8) & 0xFF) as u8;
        row[x * 4 + 2] = ((px >> 16) & 0xFF) as u8;
        row[x * 4 + 3] = 0xFF;
    }
    for y in 0..h {
        buf[y * stride..(y + 1) * stride].copy_from_slice(&row);
    }

    let put = |buf: &mut [u8], x: i32, y: i32, px: u32| {
        if x < 0 || y < 0 || x >= w as i32 || y >= h as i32 {
            return;
        }
        let o = y as usize * stride + x as usize * 4;
        buf[o] = (px & 0xFF) as u8;
        buf[o + 1] = ((px >> 8) & 0xFF) as u8;
        buf[o + 2] = ((px >> 16) & 0xFF) as u8;
        buf[o + 3] = 0xFF;
    };

    // Drifting horizontal band. Its position is the quickest way to tell a
    // frozen picture from a live one.
    let band_y = ((phase * 0.37).fract() * h as f32) as i32;
    for y in band_y..band_y + 24 {
        for x in 0..w as i32 {
            put(buf, x, y, 0x00FF_FFFF);
        }
    }

    // Ring. A circle that renders as an ellipse means the aspect ratio is off;
    // a circle that never appears means the row stride is wrong.
    let cx = w as i32 / 2;
    let cy = h as i32 / 2;
    let r = 140i32;
    let (outer, inner) = ((r + 3) * (r + 3), (r - 3) * (r - 3));
    for y in (cy - r - 4)..=(cy + r + 4) {
        for x in (cx - r - 4)..=(cx + r + 4) {
            let d2 = (x - cx) * (x - cx) + (y - cy) * (y - cy);
            if d2 <= outer && d2 >= inner {
                put(buf, x, y, 0x0000_0000);
            }
        }
    }

    // Orientation marker, drawn last so nothing can cover it. The bars and the
    // ring are symmetric about both axes, so a flipped picture is otherwise
    // indistinguishable from a correct one until real video shows up. Grey is
    // used because it appears nowhere else in the pattern: if this square is
    // not in the top-left corner, the image is mirrored, inverted, or both.
    for y in 16..80 {
        for x in 16..80 {
            put(buf, x, y, 0x0080_8080);
        }
    }
}

// ---------------------------------------------------------------------------

fn publish(shm: &Shm, pixels: &[u8]) {
    let frame = shm.frame_index().load(Ordering::Relaxed) & !1;
    let back = 1 - shm.active_buffer().load(Ordering::Relaxed).min(1);
    shm.frame_index()
        .store(frame.wrapping_add(1), Ordering::SeqCst);
    unsafe {
        std::ptr::copy_nonoverlapping(pixels.as_ptr(), shm.pixels(back), pixels.len());
        let tick = windows::Win32::System::SystemInformation::GetTickCount();
        std::ptr::write_volatile(shm.base().add(32) as *mut u32, tick);
    }
    shm.active_buffer().store(back, Ordering::Release);
    // Zero is reserved for no signal, including at the 32-bit wraparound.
    shm.frame_index()
        .store(frame.wrapping_add(2).max(2), Ordering::Release);
}
fn status(path: &Option<String>, message: &str) {
    println!("{message}");
    if let Some(path) = path {
        let _ = std::fs::write(path, message);
    }
}
fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let value = |key: &str| {
        args.iter()
            .position(|a| a == key)
            .and_then(|i| args.get(i + 1))
            .cloned()
    };
    let status_path = value("--status");
    let test = args.iter().any(|s| s == "--test");
    let endpoint = if test {
        None
    } else {
        match value("--url")
            .ok_or("Use --url http://PHONE:8080/stream or --test".into())
            .and_then(|v| mjpeg::Endpoint::parse(&v))
        {
            Ok(e) => Some(e),
            Err(e) => {
                status(&status_path, &e);
                std::process::exit(1);
            }
        }
    };
    let mut shm = match Shm::create() {
        Ok(s) => s,
        Err(e) => {
            status(&status_path, &e);
            std::process::exit(1);
        }
    };
    shm.frame_index().store(1, Ordering::SeqCst);
    {
        let hdr = shm.header();
        hdr.magic = MAGIC;
        hdr.version = VERSION;
        hdr.width = WIDTH;
        hdr.height = HEIGHT;
        hdr.stride = WIDTH * 4;
        hdr.format = FMT_BGRA;
        hdr.active_buffer = 0;
        hdr.reserved = [0; 8];
    }
    shm.frame_index().store(0, Ordering::Release);
    let mut pixels = vec![0; video::W * video::H * 4];
    if test {
        status(&status_path, "TEST - 1280 x 720 / 30 fps");
        let start = Instant::now();
        let mut deadline = Instant::now();
        loop {
            draw(&mut pixels, start.elapsed().as_secs_f32());
            publish(&shm, &pixels);
            deadline += Duration::from_nanos(1_000_000_000 / FPS as u64);
            thread::sleep(deadline.saturating_duration_since(Instant::now()));
        }
    }
    // The socket reader continuously drains the network into a single slot.
    // Decoding never causes a queue of increasingly old JPEG frames.
    use std::sync::{Arc, Condvar, Mutex};
    struct Slot {
        packet: Option<mjpeg::Packet>,
        error: Option<String>,
    }
    let shared = Arc::new((
        Mutex::new(Slot {
            packet: None,
            error: None,
        }),
        Condvar::new(),
    ));
    let network = shared.clone();
    let endpoint = endpoint.unwrap();
    thread::spawn(move || loop {
        let result = (|| -> Result<(), String> {
            let mut stream = endpoint.connect()?;
            loop {
                let packet = stream.next().map_err(|e| e.to_string())?;
                let mut slot = network.0.lock().unwrap();
                slot.packet = Some(packet);
                slot.error = None;
                network.1.notify_one();
            }
        })();
        {
            let mut slot = network.0.lock().unwrap();
            slot.packet = None;
            slot.error = result.err();
            network.1.notify_one();
        }
        thread::sleep(Duration::from_secs(1));
    });
    status(&status_path, "Connecting to phone...");
    let mut last_report = Instant::now();
    let mut frames = 0;
    let mut last_error = String::new();
    let mut decode_ms = 0.0;
    loop {
        let (packet, error) = {
            let slot = shared.0.lock().unwrap();
            let (mut slot, _) = shared
                .1
                .wait_timeout_while(slot, Duration::from_secs(1), |s| {
                    s.packet.is_none() && s.error.is_none()
                })
                .unwrap();
            (slot.packet.take(), slot.error.take())
        };
        if let Some(e) = error {
            shm.frame_index().store(0, Ordering::SeqCst);
            if last_error != e {
                status(&status_path, &format!("Reconnecting: {e}"));
                last_error = e;
            }
        }
        if let Some(packet) = packet {
            let begin = Instant::now();
            match video::decode(&packet.jpeg, packet.rotation, &mut pixels) {
                Ok((w, h)) => {
                    publish(&shm, &pixels);
                    frames += 1;
                    decode_ms += begin.elapsed().as_secs_f64() * 1000.0;
                    if !last_error.is_empty() || last_report.elapsed() >= Duration::from_secs(1) {
                        status(
                            &status_path,
                            &format!(
                                "LIVE - {w} x {h} / {:.1} fps / {:.1} ms decode",
                                frames as f64 / last_report.elapsed().as_secs_f64(),
                                decode_ms / frames as f64
                            ),
                        );
                        frames = 0;
                        decode_ms = 0.0;
                        last_report = Instant::now();
                        last_error.clear();
                    }
                }
                Err(e) => {
                    shm.frame_index().store(0, Ordering::SeqCst);
                    status(&status_path, &format!("Invalid video: {e}"));
                }
            }
        }
    }
}
