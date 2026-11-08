# 🗺 Roadmap

[← Back to README](README.md)

## ✅ 2.0

- [x] Camera2 with manual ISO, shutter, EV, white balance, focus, zoom, OIS/EIS, torch
- [x] Hardware H.264 / HEVC, 4K30 and 1080p60
- [x] GPU decoding on the PC, NV12 virtual camera up to 4K, event-driven delivery
- [x] TLS 1.3, one-time pairing, automatic discovery and reconnect
- [x] Tauri desktop app with live preview and full camera control
- [x] New Compose UI on the phone, new icons
- [x] Thermal protection and automatic camera recovery
- [x] System-wide registration for apps run as administrator

## 🔭 Next

- [ ] Device reliability: runtime self-checks, device reports, quirks table and an automated per-phone test matrix: see the [plan](docs/plans/device-reliability.md)
- [ ] Portrait (background blur) through Camera2 Extensions (`BOKEH`), done on the phone with no PC cost, where the phone supports it

- [ ] Media Foundation virtual camera (Windows 11) for apps that only use Media Foundation (Windows Camera, Discord, Zoom, Teams): see the [plan](docs/plans/media-foundation-camera.md)
- [ ] Microphone streaming
- [ ] One-click USB mode (`adb forward`, tethering as fallback): see the [plan](docs/plans/usb-connection.md)
- [ ] Real 60 fps on Samsung Exynos phones: constrained high-speed sessions never deliver frames on a Galaxy S21+ (all lenses), so those modes are detected and hidden. Find the session setup Samsung's HAL accepts
- [ ] Rotation and scaling on the GPU on the PC side
- [ ] Custom white balance in Kelvin
- [ ] Tap to focus / expose on the preview
- [ ] Installer and auto-update
