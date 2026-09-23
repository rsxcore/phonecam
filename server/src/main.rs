//! Command-line receiver, mainly for development and diagnostics.
//!
//! `phonecam-server [--connect HOST[:PORT]] [--test] [--status FILE]`
//! prints one JSON event per line and forwards JSON lines from stdin to the
//! phone as control messages.

use std::io::BufRead;
use std::sync::Arc;

use phonecam_core::Engine;
use serde_json::Value;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let value = |key: &str| args.iter().position(|a| a == key).and_then(|i| args.get(i + 1)).cloned();
    let status_file = value("--status");
    let events = Arc::new(move |event: Value| {
        println!("{event}");
        if let (Some(path), Some(text)) = (&status_file, event.get("text").and_then(Value::as_str)) {
            let _ = std::fs::write(path, text);
        }
    });

    let address = match value("--connect").or_else(|| value("--url")).as_deref().map(phonecam_core::proto::parse_address) {
        Some(Ok(a)) => Some(a),
        Some(Err(e)) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
        None => None,
    };
    let engine = match Engine::start(address, events, None) {
        Ok(e) => e,
        Err(e) => {
            println!("{}", serde_json::json!({ "event": "status", "text": e }));
            std::process::exit(1);
        }
    };
    if args.iter().any(|a| a == "--test") {
        engine.set_test_pattern(true);
    }
    for line in std::io::stdin().lock().lines().map_while(Result::ok) {
        if serde_json::from_str::<Value>(&line).is_ok() {
            engine.send_control(&line);
        }
    }
    // stdin closed (e.g. started without a console): keep serving the camera.
    loop {
        std::thread::park();
    }
}
