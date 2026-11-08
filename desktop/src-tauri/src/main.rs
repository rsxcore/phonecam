#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod vcam;

use std::sync::{Arc, Mutex};

use phonecam_core::{identity, proto, Engine, VideoPacket};
use serde::Serialize;
use serde_json::{json, Value};
use tauri::ipc::{Channel, InvokeResponseBody};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager, State, WindowEvent};

#[derive(Default)]
struct AppState {
    engine: Mutex<Option<Engine>>,
    preview: Arc<Mutex<Option<Channel<InvokeResponseBody>>>>,
    /// Events that arrived before the page subscribed; replayed on `ready`.
    history: Arc<Mutex<Vec<Value>>>,
    /// The current stream's codec description, so a preview can start mid-stream.
    last_config: Arc<Mutex<Option<Vec<u8>>>>,
}

#[derive(Serialize)]
struct PairedPhone {
    fingerprint: String,
    name: String,
    address: String,
}

fn engine(state: &State<AppState>) -> Option<Engine> {
    state.engine.lock().unwrap().clone()
}

/// Called by the page once its listeners are in place.
#[tauri::command]
fn ready(state: State<AppState>) -> Vec<Value> {
    state.history.lock().unwrap().clone()
}

#[tauri::command]
fn control(state: State<AppState>, set: Value) -> bool {
    engine(&state).map(|e| e.send_control(&json!({ "set": set }).to_string())).unwrap_or(false)
}

#[tauri::command]
fn connect(state: State<AppState>, address: Option<String>) -> Result<(), String> {
    let address = match address.filter(|a| !a.trim().is_empty()) {
        Some(a) => Some(proto::parse_address(&a)?),
        None => None,
    };
    if let Some(e) = engine(&state) {
        e.connect_to(address);
    }
    Ok(())
}

#[tauri::command]
fn test_pattern(state: State<AppState>, on: bool) {
    if let Some(e) = engine(&state) {
        e.set_test_pattern(on);
    }
}

/// Starts sending encoded video to the page for the live preview.
#[tauri::command]
fn start_preview(state: State<AppState>, channel: Channel<InvokeResponseBody>) {
    if let Some(config) = state.last_config.lock().unwrap().clone() {
        let _ = channel.send(InvokeResponseBody::Raw(config));
    }
    *state.preview.lock().unwrap() = Some(channel);
    if let Some(e) = engine(&state) {
        e.set_preview(true);
    }
}

#[tauri::command]
fn stop_preview(state: State<AppState>) {
    *state.preview.lock().unwrap() = None;
    if let Some(e) = engine(&state) {
        e.set_preview(false);
    }
}

/// Diagnostics from the page (the release build has no devtools).
#[tauri::command]
fn js_log(line: String) {
    use std::io::Write;
    let path = std::env::temp_dir().join("phonecam-ui.log");
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(f, "{line}");
    }
}

#[tauri::command]
fn phones() -> Vec<PairedPhone> {
    identity::phones()
        .into_iter()
        .map(|p| PairedPhone { fingerprint: p.fingerprint, name: p.name, address: p.address })
        .collect()
}

#[tauri::command]
fn forget_phone(state: State<AppState>, fingerprint: String) {
    identity::forget(&fingerprint);
    if let Some(e) = engine(&state) {
        e.connect_to(None);
    }
}

#[tauri::command]
fn reinstall_camera() -> Result<String, String> {
    vcam::install().map(|p| p.display().to_string())
}

#[tauri::command]
fn uninstall_camera() -> Result<(), String> {
    vcam::uninstall()
}

/// Makes the camera visible to apps running as administrator (one UAC prompt).
#[tauri::command]
async fn register_camera_system() -> Result<bool, String> {
    vcam::register_system_elevated()?;
    Ok(vcam::system_registered())
}

#[tauri::command]
fn reconnect(state: State<AppState>) {
    if let Some(e) = engine(&state) {
        e.reconnect();
    }
}

/// Answers the `approve` event for a new phone that dialled this PC.
#[tauri::command]
fn approve_phone(state: State<AppState>, ok: bool) {
    if let Some(e) = engine(&state) {
        e.approve(ok);
    }
}

/// Lets phones reach the listening port through Windows Firewall on every
/// network profile; a cable to the modem is often a "Public" network, where
/// Windows blocks incoming connections by default (one UAC prompt).
#[tauri::command]
async fn allow_firewall() -> Result<(), String> {
    vcam::run_elevated(windows::core::w!("--allow-firewall"))
}

/// RivaTuner Statistics Server (bundled with MSI Afterburner) injects its
/// overlay into WebView2, and that hook crashes WebView2 as soon as the
/// preview draws video: the window turns black while the camera keeps working.
fn rtss_running() -> bool {
    use std::os::windows::process::CommandExt;
    std::process::Command::new("tasklist")
        .args(["/FI", "IMAGENAME eq RTSS.exe", "/NH"])
        .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
        .output()
        .is_ok_and(|o| String::from_utf8_lossy(&o.stdout).contains("RTSS.exe"))
}

/// RivaTuner's per-app profile for WebView2. Disabling hooking there is what
/// RivaTuner's own "Application detection level: None" writes.
fn rtss_profile() -> std::path::PathBuf {
    let base = std::env::var_os("ProgramFiles(x86)").map(std::path::PathBuf::from).unwrap_or_else(|| "C:\\Program Files (x86)".into());
    base.join("RivaTuner Statistics Server").join("Profiles").join("msedgewebview2.exe.cfg")
}

fn rtss_excluded() -> bool {
    std::fs::read_to_string(rtss_profile()).is_ok_and(|s| s.lines().any(|l| l.trim() == "EnableHooking=0"))
}

/// Elevated helper for `exclude_rtss`. Writes only the hooking switch, so an
/// existing profile for WebView2 loses nothing else.
fn exclude_from_rtss() -> bool {
    let path = rtss_profile();
    if !path.parent().is_some_and(|p| p.is_dir()) {
        return false;
    }
    let old = std::fs::read_to_string(&path).unwrap_or_default();
    let mut out = String::new();
    let (mut in_hooking, mut done) = (false, false);
    for line in old.lines() {
        let t = line.trim();
        if t.starts_with('[') {
            if in_hooking && !done {
                out.push_str("EnableHooking=0\r\n");
                done = true;
            }
            in_hooking = t.eq_ignore_ascii_case("[Hooking]");
        } else if in_hooking && t.starts_with("EnableHooking=") {
            out.push_str("EnableHooking=0\r\n");
            done = true;
            continue;
        }
        out.push_str(line);
        out.push_str("\r\n");
    }
    if !done {
        if !in_hooking {
            out.push_str("[Hooking]\r\n");
        }
        out.push_str("EnableHooking=0\r\n");
    }
    std::fs::write(&path, out).is_ok()
}

/// Turns RivaTuner's overlay off for WebView2 (one UAC prompt).
#[tauri::command]
async fn exclude_rtss() -> Result<(), String> {
    vcam::run_elevated(windows::core::w!("--exclude-rtss"))?;
    if rtss_excluded() {
        Ok(())
    } else {
        Err("RivaTuner was not found in its usual folder. Add msedgewebview2.exe in RivaTuner and set Application detection level to None.".into())
    }
}

/// Elevated helper for `allow_firewall`: one inbound rule for the listening port.
fn add_firewall_rule() -> bool {
    use std::os::windows::process::CommandExt;
    let netsh = |args: &[&str]| {
        std::process::Command::new("netsh")
            .args(["advfirewall", "firewall"])
            .args(args)
            .creation_flags(0x0800_0000) // CREATE_NO_WINDOW
            .status()
            .is_ok_and(|s| s.success())
    };
    let name = "name=PhoneCam (phones connecting)";
    netsh(&["delete", "rule", name]);
    let port = format!("localport={}", proto::LISTEN_PORT);
    netsh(&["add", "rule", name, "dir=in", "action=allow", "protocol=TCP", &port, "profile=any"])
}

/// Binary preview packets: `0x10 codec w h fps` for a new stream, `0x11 flags
/// pts rotation data` per frame. Mirrors the phone protocol so the page can
/// feed WebCodecs directly.
fn encode_packet(packet: &VideoPacket) -> Vec<u8> {
    match packet {
        VideoPacket::Config { codec, width, height, fps } => {
            let mut b = vec![0x10, if *codec == proto::Codec::Hevc { 2 } else { 1 }];
            b.extend_from_slice(&(*width as u16).to_le_bytes());
            b.extend_from_slice(&(*height as u16).to_le_bytes());
            b.extend_from_slice(&(*fps as u16).to_le_bytes());
            b
        }
        VideoPacket::Frame { key, pts_us, rotation, data } => {
            let mut b = Vec::with_capacity(12 + data.len());
            b.push(0x11);
            b.push(*key as u8);
            b.extend_from_slice(&pts_us.to_le_bytes());
            b.extend_from_slice(&rotation.to_le_bytes());
            b.extend_from_slice(data);
            b
        }
    }
}

fn start_engine(app: &AppHandle) {
    let state = app.state::<AppState>();
    let (history, handle) = (state.history.clone(), app.clone());
    // PHONECAM_LOG=path writes every engine event to a file, for diagnostics.
    let log = std::env::var_os("PHONECAM_LOG").and_then(|p| std::fs::OpenOptions::new().create(true).append(true).open(p).ok());
    let log = Mutex::new(log);
    let events = Arc::new(move |event: Value| {
        if let Some(f) = log.lock().unwrap().as_mut() {
            use std::io::Write;
            let _ = writeln!(f, "{event}");
        }
        {
            // Keep what a freshly opened page needs to draw the current state.
            let mut h = history.lock().unwrap();
            if matches!(event.get("event").and_then(Value::as_str), Some("stats" | "state" | "status")) {
                h.retain(|e| e.get("event") != event.get("event"));
            }
            h.push(event.clone());
            if h.len() > 64 {
                h.remove(0);
            }
        }
        let _ = handle.emit("engine", event);
    });
    let preview = state.preview.clone();
    let last_config = state.last_config.clone();
    let video = Arc::new(move |packet: VideoPacket| {
        let bytes = encode_packet(&packet);
        if matches!(packet, VideoPacket::Config { .. }) {
            *last_config.lock().unwrap() = Some(bytes.clone());
        }
        if let Some(channel) = preview.lock().unwrap().as_ref() {
            let _ = channel.send(InvokeResponseBody::Raw(bytes));
        }
    });
    match Engine::start(None, events.clone(), Some(video)) {
        Ok(e) => *state.engine.lock().unwrap() = Some(e),
        Err(e) => events(json!({ "event": "fatal", "text": e })),
    }
}

fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}

fn main() {
    // Elevated helper mode, started by `register_camera_system`.
    if std::env::args().any(|a| a == "--register-system") {
        std::process::exit(match vcam::register_system() {
            Ok(()) => 0,
            Err(_) => 2,
        });
    }
    if std::env::args().any(|a| a == "--allow-firewall") {
        std::process::exit(if add_firewall_rule() { 0 } else { 2 });
    }
    if std::env::args().any(|a| a == "--exclude-rtss") {
        std::process::exit(if exclude_from_rtss() { 0 } else { 2 });
    }
    // Checked once: the warning only matters while RivaTuner can still hook WebView2.
    let rtss = rtss_running() && !rtss_excluded();
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            ready,
            js_log,
            control,
            connect,
            test_pattern,
            start_preview,
            stop_preview,
            phones,
            forget_phone,
            reinstall_camera,
            uninstall_camera,
            register_camera_system,
            approve_phone,
            allow_firewall,
            reconnect,
            exclude_rtss
        ])
        .setup(move |app| {
            let handle = app.handle().clone();
            let camera = vcam::install();
            let camera_event = match &camera {
                Ok(path) => json!({
                    "event": "camera", "ok": true, "path": path.display().to_string(),
                    "system": vcam::system_registered(),
                }),
                Err(e) => json!({ "event": "camera", "ok": false, "text": e }),
            };
            app.state::<AppState>().history.lock().unwrap().push(camera_event);
            if rtss {
                app.state::<AppState>().history.lock().unwrap().push(json!({ "event": "rtss" }));
            }
            start_engine(&handle);

            // Closing the window keeps the camera running from the tray, so a
            // call or a recording never loses the picture by accident.
            let open = MenuItem::with_id(app, "open", "Open PhoneCam", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &quit])?;
            TrayIconBuilder::with_id("main")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("PhoneCam")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
                let _ = window.emit("hidden", ());
            }
        })
        .run(tauri::generate_context!())
        .expect("PhoneCam failed to start");
}
