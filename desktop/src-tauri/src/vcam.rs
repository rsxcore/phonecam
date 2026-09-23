//! The PhoneCam virtual camera: a DirectShow filter DLL embedded in this
//! executable, unpacked next to the user's data and registered for the
//! current user only (no administrator rights needed).

use std::path::PathBuf;

use windows::core::{s, HSTRING};
use windows::Win32::Foundation::HMODULE;
use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};

static DLL: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../vcam/out/PhoneCam.dll"));

type RegisterFn = unsafe extern "system" fn() -> i32;

fn install_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    let digest = ring::digest::digest(&ring::digest::SHA256, DLL);
    let tag: String = digest.as_ref()[..6].iter().map(|b| format!("{b:02x}")).collect();
    // One folder per build: apps that already loaded an older DLL keep it,
    // and updating never has to overwrite a file that is in use.
    base.join("PhoneCam").join(format!("camera-{tag}"))
}

/// Unpacks and registers the camera. Returns the DLL path.
pub fn install() -> Result<PathBuf, String> {
    let dir = install_dir();
    let path = dir.join("PhoneCam.dll");
    if std::fs::read(&path).map(|b| b != DLL).unwrap_or(true) {
        std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
        let temp = dir.join("PhoneCam.dll.tmp");
        std::fs::write(&temp, DLL).map_err(|e| e.to_string())?;
        std::fs::rename(&temp, &path).map_err(|e| e.to_string())?;
    }
    call(&path, "DllRegisterServer")?;
    Ok(path)
}

/// Removes the camera from the list of devices.
pub fn uninstall() -> Result<(), String> {
    call(&install_dir().join("PhoneCam.dll"), "DllUnregisterServer")
}

fn call(path: &PathBuf, export: &str) -> Result<(), String> {
    unsafe {
        let module: HMODULE = LoadLibraryW(&HSTRING::from(path.as_os_str())).map_err(|e| format!("Cannot load the camera: {e}"))?;
        let proc = match export {
            "DllRegisterServer" => GetProcAddress(module, s!("DllRegisterServer")),
            _ => GetProcAddress(module, s!("DllUnregisterServer")),
        }
        .ok_or("The camera DLL is damaged")?;
        let f: RegisterFn = std::mem::transmute(proc);
        let hr = f();
        if hr < 0 {
            return Err(format!("Camera registration failed (0x{:08X})", hr as u32));
        }
    }
    Ok(())
}
