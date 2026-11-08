# Plan: one-click USB connection

[← Back to Roadmap](../../ROADMAP.md)

**Status:** planned, not started. **Goal:** when the phone is plugged into the PC with a cable, PhoneCam uses the cable on its own, ahead of Wi-Fi, with no addresses to type.

## Why

A cable is the most reliable way to stream:

- **Stable latency.** No Wi-Fi interference, channel changes or roaming, so no stutter.
- **More bandwidth than the stream needs.** adb over USB 2.0 carries tens of Mbps; PhoneCam sends 20–50 Mbps.
- **No network setup.** It works whatever routers, NAT or firewalls sit between the phone and the PC (see the phone-dials-PC work for how much those got in the way).
- **The phone charges** while it streams.

## What exists today

| Piece | State |
|:--|:--|
| Manual USB | Works: run `adb forward tcp:8080 tcp:8080`, then in the desktop app go to Settings → Connect by address → `127.0.0.1` |
| UI label | `viaUsb` in `desktop/src/App.svelte` shows "USB" for a `127.0.0.1` connection |
| Automatic USB | **Missing.** `find_phone` in `server/src/engine.rs` never tries USB |
| Docs | `README.md` claims the PC finds the phone "over Wi-Fi or USB" automatically. That is wrong until this plan ships |

## Approach A: adb (recommended)

The PC finds a phone attached over USB and forwards a local port to it.

1. **Find adb**, in order: bundled with the app, `%LOCALAPPDATA%\Android\Sdk\platform-tools`, then `PATH`.
   - Check the redistribution terms of Google's prebuilt platform-tools **before** bundling. If bundling isn't allowed, use only an installed adb and say so in the UI. adb's source is Apache-2.0; the prebuilt binaries ship under the Android SDK license.
2. **Detect the device.** Run `adb devices` and parse each line's state: `device` (ready), `unauthorized` (accept the prompt on the phone), or `offline`.
3. **Forward a port.** Run `adb -s <serial> forward tcp:<free port> tcp:8080` and try `127.0.0.1:<port>` **first** in `find_phone`, before UDP discovery and the subnet scan.
4. **Run adb safely.** Every call uses `CREATE_NO_WINDOW` (as `add_firewall_rule` in `desktop/src-tauri/src/main.rs` does) and a short timeout. It runs off the connection thread and must never block the dial-in listener on port 8090.
5. **Fall back.** When the cable is unplugged, the connection drops, the next search finds no device, and Wi-Fi takes over. Remove the forward with `adb forward --remove` when done.
6. **Show the state in the UI** through new engine events: `usb: ready | unauthorized | off`. Add a short how-to for turning on USB debugging (Settings → About phone → tap Build number 7 times → Developer options → USB debugging).

**Trade-off:** the user must turn on USB debugging once.

## Approach B: USB tethering (fallback, no developer options)

1. On the phone, turn on Settings → Connections → Mobile Hotspot and Tethering → **USB tethering**.
2. The phone and the PC now share a small USB network (for example `192.168.42.x`).
3. The existing **phone-dials-PC** path (port 8090) works over it unchanged. The PC search (port 8080) works too once the phone reports its address in HELLO.

**Trade-off:** Windows may send the PC's internet traffic through the phone, using its mobile data. Document how to raise the interface metric, or warn the user. Don't automate this.

## Tasks

- [ ] `server/src/usb.rs`: find adb, parse `adb devices`, create and remove forwards, with unit tests for the parser.
- [ ] `engine.rs`: try USB first in `find_phone`; emit `usb` events.
- [ ] Desktop UI: USB status in the top bar, the "USB" tag, and how-to text for each state.
- [ ] Decide on bundling adb after the license check. Update `THIRD_PARTY.md` and `licenses/` if it gets bundled.
- [ ] README: fix the USB claim and add a "Connect with a cable" section, including tethering as approach B.
- [ ] ROADMAP: tick "One-click USB mode".

## Done when

- A Galaxy S21+ with USB debugging on connects over the cable with nothing typed, and the desktop app shows **USB**.
- Unplugging the cable falls back to Wi-Fi within a few seconds; plugging it back in returns to USB.
- An unauthorized device shows "Accept the USB debugging prompt on your phone".
- No console windows flash, and the app still starts when adb is missing.

## How to verify

- `cargo test --release --manifest-path server\Cargo.toml` (adb output parser).
- Manual: plug in, unplug, replug, revoke USB debugging authorizations on the phone, and test with adb missing.
- Logs: run with `PHONECAM_LOG=path` and confirm `usb` events and `connected` with a `127.0.0.1` address.
