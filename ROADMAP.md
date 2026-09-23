# 🗺 Roadmap

[← Back to README](README.md)

## ✅ Shipped in 1.1

- [x] MJPEG receiving and real video output through DirectShow
- [x] Fixed YUV handling, orientation, and camera / service lifecycle
- [x] Reconnect, bounded queues, heartbeat, second-writer protection
- [x] Fixed COM lifetime, `AM_MEDIA_TYPE` ownership and HKCU registration
- [x] Single Win32 EXE: embedded DLL / receiver, preview, code, discovery, tray
- [x] Optimized APK with a permanent local release signature
- [x] Unit / native / integration tests and documentation

## ❓ Unconfirmed without a physical phone

- [ ] CameraX frame capture on a specific phone model, background / screen-off behavior
- [ ] Real network latency over Wi-Fi / USB, heat and battery drain
- [ ] Compatibility with each specific calling app

## 🔭 Possible improvements after measurements

- [ ] Hardware H.264: MediaCodec → Media Foundation
- [ ] Media Foundation virtual camera, if a specific client requires it
- [ ] x86 filter, microphone, QR pairing and autostart — if needed

> [!NOTE]
> This version does not claim to implement any of the future items above.
