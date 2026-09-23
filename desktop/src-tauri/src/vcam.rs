//! The PhoneCam virtual camera: a DirectShow filter DLL embedded in this
//! executable.
//!
//! It is registered for the current user on every start (no administrator
//! rights needed). Apps running as administrator — OBS often is, for game
//! capture — ignore per-user registrations, so a one-time system-wide
//! registration is offered as well; it needs a UAC prompt.

use std::path::PathBuf;

use windows::core::{s, w, HSTRING, PCWSTR};
use windows::Win32::Foundation::{CloseHandle, HMODULE};
use windows::Win32::System::LibraryLoader::{GetProcAddress, LoadLibraryW};
use windows::Win32::System::Registry::{
    RegCloseKey, RegCreateKeyExW, RegGetValueW, RegSetValueExW, HKEY, HKEY_LOCAL_MACHINE, KEY_WRITE, REG_OPTION_NON_VOLATILE,
    REG_SZ, RRF_RT_REG_SZ,
};
use windows::Win32::System::Threading::{GetExitCodeProcess, WaitForSingleObject, INFINITE};
use windows::Win32::UI::Shell::{ShellExecuteExW, SEE_MASK_NOCLOSEPROCESS, SHELLEXECUTEINFOW};

static DLL: &[u8] = include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/../../vcam/out/PhoneCam.dll"));

const CLSID: &str = "{32A4046A-5361-491F-AD92-F30755E23446}";
const VIDEO_INPUT_CATEGORY: &str = "{860BB310-5D01-11D0-BD3B-00A0C911CE86}";

type RegisterFn = unsafe extern "system" fn() -> i32;

fn tag() -> String {
    let digest = ring::digest::digest(&ring::digest::SHA256, DLL);
    digest.as_ref()[..6].iter().map(|b| format!("{b:02x}")).collect()
}

fn user_dir() -> PathBuf {
    let base = std::env::var_os("LOCALAPPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    // One folder per build: apps that already loaded an older DLL keep it,
    // and updating never has to overwrite a file that is in use.
    base.join("PhoneCam").join(format!("camera-{}", tag()))
}

fn system_path() -> PathBuf {
    let base = std::env::var_os("ProgramData").map(PathBuf::from).unwrap_or_else(|| PathBuf::from(r"C:\ProgramData"));
    base.join("PhoneCam").join(format!("camera-{}", tag())).join("PhoneCam.dll")
}

fn unpack(path: &PathBuf) -> Result<(), String> {
    if std::fs::read(path).map(|b| b != DLL).unwrap_or(true) {
        std::fs::create_dir_all(path.parent().unwrap()).map_err(|e| e.to_string())?;
        let temp = path.with_extension("dll.tmp");
        std::fs::write(&temp, DLL).map_err(|e| e.to_string())?;
        std::fs::rename(&temp, path).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Unpacks and registers the camera for the current user. Returns the DLL path.
pub fn install() -> Result<PathBuf, String> {
    let path = user_dir().join("PhoneCam.dll");
    unpack(&path)?;
    call(&path, "DllRegisterServer")?;
    Ok(path)
}

/// Removes the per-user registration.
pub fn uninstall() -> Result<(), String> {
    call(&user_dir().join("PhoneCam.dll"), "DllUnregisterServer")
}

/// True when apps running as administrator can load this build's camera.
pub fn system_registered() -> bool {
    let key = HSTRING::from(format!(r"Software\Classes\CLSID\{CLSID}\InprocServer32"));
    let mut buf = vec![0u16; 1024];
    let mut size = (buf.len() * 2) as u32;
    let ok = unsafe {
        RegGetValueW(HKEY_LOCAL_MACHINE, &key, PCWSTR::null(), RRF_RT_REG_SZ, None, Some(buf.as_mut_ptr() as *mut _), Some(&mut size))
    }
    .is_ok();
    if !ok {
        return false;
    }
    let registered = String::from_utf16_lossy(&buf[..(size as usize / 2).saturating_sub(1)]);
    PathBuf::from(registered) == system_path() && system_path().exists()
}

/// Re-runs this executable elevated with `--register-system` (one UAC prompt).
pub fn register_system_elevated() -> Result<(), String> {
    let exe = HSTRING::from(std::env::current_exe().map_err(|e| e.to_string())?.as_os_str());
    let mut info = SHELLEXECUTEINFOW {
        cbSize: std::mem::size_of::<SHELLEXECUTEINFOW>() as u32,
        fMask: SEE_MASK_NOCLOSEPROCESS,
        lpVerb: w!("runas"),
        lpFile: PCWSTR(exe.as_ptr()),
        lpParameters: w!("--register-system"),
        nShow: 0,
        ..Default::default()
    };
    unsafe {
        ShellExecuteExW(&mut info).map_err(|_| "Administrator permission was not granted".to_string())?;
        WaitForSingleObject(info.hProcess, INFINITE);
        let mut code = 1u32;
        let _ = GetExitCodeProcess(info.hProcess, &mut code);
        let _ = CloseHandle(info.hProcess);
        if code != 0 {
            return Err(format!("System registration failed (code {code})"));
        }
    }
    Ok(())
}

/// Runs in the elevated helper process: copies the DLL where every user can
/// read it and registers it under HKLM, replacing any stale registration
/// left by an older PhoneCam.
pub fn register_system() -> Result<(), String> {
    let path = system_path();
    unpack(&path)?;
    let dll = path.display().to_string();
    let classes = r"Software\Classes\CLSID";
    set(&format!(r"{classes}\{CLSID}"), None, "PhoneCam")?;
    set(&format!(r"{classes}\{CLSID}\InprocServer32"), None, &dll)?;
    set(&format!(r"{classes}\{CLSID}\InprocServer32"), Some("ThreadingModel"), "Both")?;
    set(&format!(r"{classes}\{VIDEO_INPUT_CATEGORY}\Instance\{CLSID}"), Some("CLSID"), CLSID)?;
    set(&format!(r"{classes}\{VIDEO_INPUT_CATEGORY}\Instance\{CLSID}"), Some("FriendlyName"), "PhoneCam")?;
    Ok(())
}

fn set(key: &str, name: Option<&str>, value: &str) -> Result<(), String> {
    unsafe {
        let mut hkey = HKEY::default();
        RegCreateKeyExW(HKEY_LOCAL_MACHINE, &HSTRING::from(key), 0, None, REG_OPTION_NON_VOLATILE, KEY_WRITE, None, &mut hkey, None)
            .ok()
            .map_err(|e| format!("{key}: {e}"))?;
        let data: Vec<u16> = value.encode_utf16().chain(Some(0)).collect();
        let bytes = std::slice::from_raw_parts(data.as_ptr() as *const u8, data.len() * 2);
        let name = name.map(HSTRING::from);
        let result = RegSetValueExW(hkey, name.as_ref().map(|n| PCWSTR(n.as_ptr())).unwrap_or(PCWSTR::null()), 0, REG_SZ, Some(bytes));
        let _ = RegCloseKey(hkey);
        result.ok().map_err(|e| format!("{key}: {e}"))
    }
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
