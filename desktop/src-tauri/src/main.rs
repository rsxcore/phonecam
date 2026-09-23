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
    tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .manage(AppState::default())
        .invoke_handler(tauri::generate_handler![
            ready,
            control,
            connect,
            test_pattern,
            start_preview,
            stop_preview,
            phones,
            forget_phone,
            reinstall_camera,
            uninstall_camera
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            let camera = vcam::install();
            let camera_event = match &camera {
                Ok(path) => json!({ "event": "camera", "ok": true, "path": path.display().to_string() }),
                Err(e) => json!({ "event": "camera", "ok": false, "text": e }),
            };
            app.state::<AppState>().history.lock().unwrap().push(camera_event);
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
