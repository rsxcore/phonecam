//! Shared memory writer. Mirrors `vcam/PhoneCamProtocol.h`; the assertions
//! below fail the build if the two ever drift apart.

use std::mem::{offset_of, size_of};
use std::sync::atomic::{AtomicU32, Ordering};

use windows::core::PCWSTR;
use windows::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, HANDLE, INVALID_HANDLE_VALUE};
use windows::Win32::System::Memory::{
    CreateFileMappingW, MapViewOfFile, UnmapViewOfFile, FILE_MAP_ALL_ACCESS, MEMORY_MAPPED_VIEW_ADDRESS, PAGE_READWRITE,
};
use windows::Win32::System::SystemInformation::GetTickCount;
use windows::Win32::System::Threading::CreateMutexW;

const SHM_NAME: &str = r"Local\PhoneCam_Frame_v2";
const WRITER_MUTEX: &str = r"Local\PhoneCam_Writer_v2";
const MAGIC: u32 = 0x4D41_4350; // 'PCAM'
const VERSION: u32 = 2;
const FMT_NV12: u32 = 2;
const PIXELS_OFFSET: usize = 64;
pub const MAX_WIDTH: usize = 3840;
pub const MAX_PIXELS: usize = 3840 * 2160;
const BUFFERS: u32 = 2;
const BUF_BYTES: usize = MAX_PIXELS * 3 / 2;
const SHM_SIZE: usize = PIXELS_OFFSET + BUFFERS as usize * BUF_BYTES;

#[repr(C)]
struct Header {
    magic: u32,
    version: u32,
    width: u32,
    height: u32,
    stride: u32,
    format: u32,
    frame_index: u32,
    active_buffer: u32,
    heartbeat: u32,
    fps: u32,
    pts_us: i64,
    reserved: [u32; 4],
}

const _: () = assert!(size_of::<Header>() == PIXELS_OFFSET);
const _: () = assert!(offset_of!(Header, frame_index) == 24);
const _: () = assert!(offset_of!(Header, active_buffer) == 28);
const _: () = assert!(offset_of!(Header, pts_us) == 40);

pub struct Shm {
    mapping: HANDLE,
    view: MEMORY_MAPPED_VIEW_ADDRESS,
    mutex: HANDLE,
}

// The view is only touched through `&mut self` or atomics.
unsafe impl Send for Shm {}

fn wide(s: &str) -> Vec<u16> {
    s.encode_utf16().chain(Some(0)).collect()
}

impl Shm {
    pub fn create() -> Result<Self, String> {
        unsafe {
            let mutex_name = wide(WRITER_MUTEX);
            let mutex = CreateMutexW(None, false, PCWSTR(mutex_name.as_ptr())).map_err(|e| e.to_string())?;
            if GetLastError() == ERROR_ALREADY_EXISTS {
                let _ = CloseHandle(mutex);
                return Err("PhoneCam is already running".into());
            }
            let name = wide(SHM_NAME);
            let mapping = CreateFileMappingW(
                INVALID_HANDLE_VALUE,
                None,
                PAGE_READWRITE,
                (SHM_SIZE >> 32) as u32,
                SHM_SIZE as u32,
                PCWSTR(name.as_ptr()),
            )
            .map_err(|e| format!("CreateFileMappingW failed: {e}"))?;
            let view = MapViewOfFile(mapping, FILE_MAP_ALL_ACCESS, 0, 0, SHM_SIZE);
            if view.Value.is_null() {
                let _ = CloseHandle(mapping);
                let _ = CloseHandle(mutex);
                return Err(format!("MapViewOfFile failed: {}", windows::core::Error::from_win32()));
            }
            let shm = Self { mapping, view, mutex };
            shm.frame_index().store(1, Ordering::SeqCst);
            let h = &mut *(shm.base() as *mut Header);
            h.magic = MAGIC;
            h.version = VERSION;
            h.format = FMT_NV12;
            h.width = 0;
            h.height = 0;
            h.stride = 0;
            h.active_buffer = 0;
            h.heartbeat = 0;
            h.fps = 30;
            h.pts_us = 0;
            h.reserved = [0; 4];
            shm.frame_index().store(0, Ordering::Release);
            Ok(shm)
        }
    }

    fn base(&self) -> *mut u8 {
        self.view.Value as *mut u8
    }

    fn frame_index(&self) -> &AtomicU32 {
        unsafe { &*(self.base().add(offset_of!(Header, frame_index)) as *const AtomicU32) }
    }

    fn active_buffer(&self) -> &AtomicU32 {
        unsafe { &*(self.base().add(offset_of!(Header, active_buffer)) as *const AtomicU32) }
    }

    /// Publishes one tightly packed NV12 frame.
    pub fn publish(&mut self, nv12: &[u8], width: usize, height: usize, fps: u32, pts_us: i64) {
        if width == 0 || height == 0 || width > MAX_WIDTH || height > MAX_WIDTH || width * height > MAX_PIXELS {
            return;
        }
        let bytes = width * height * 3 / 2;
        if nv12.len() < bytes {
            return;
        }
        let frame = self.frame_index().load(Ordering::Relaxed) & !1;
        let back = 1 - self.active_buffer().load(Ordering::Relaxed).min(1);
        self.frame_index().store(frame.wrapping_add(1), Ordering::SeqCst);
        unsafe {
            let dst = self.base().add(PIXELS_OFFSET + back as usize * BUF_BYTES);
            std::ptr::copy_nonoverlapping(nv12.as_ptr(), dst, bytes);
            let h = &mut *(self.base() as *mut Header);
            h.width = width as u32;
            h.height = height as u32;
            h.stride = width as u32;
            h.fps = fps;
            h.pts_us = pts_us;
            h.heartbeat = GetTickCount();
        }
        self.active_buffer().store(back, Ordering::Release);
        // Zero is reserved for "no signal", including at the 32-bit wraparound.
        self.frame_index().store(frame.wrapping_add(2).max(2), Ordering::Release);
    }

    /// Shows "no signal" in every app using the camera.
    pub fn clear(&mut self) {
        self.frame_index().store(0, Ordering::SeqCst);
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
