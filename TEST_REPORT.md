# 🧪 Test Report: PhoneCam 2.0

[← Back to README](README.md)

**Date:** September 23, 2026
**Phone:** Nothing Phone (1) (A063), Snapdragon 778G+, Android 15
**PC:** Windows 11 x64, NVIDIA GPU

All results below were measured on this real hardware.

## Stream quality

| Check | Result |
|:--|:--|
| 1080p30 H.264, USB | ✅ 30.01 fps, frame intervals 33.3 ms, 20 Mbps |
| 1080p60 (120 fps sensor, every 2nd frame kept) | ✅ 60.00 fps, every interval exactly 16.7 ms, 0 skips in 10 s |
| 1080p60 over Wi-Fi with TLS, 22 s | ✅ 58.3–61.3 fps per second (avg 59.9), 0 congestion drops |
| PC decoding, Media Foundation on the GPU | ✅ ~2.5 ms decode, ~5–6 ms including rotation and publish (software decode was ~10 ms) |
| DirectShow capture (ffmpeg as the client) at 1920×1080 NV12 60 fps | ✅ 480 frames in 8 s (59.88 fps), timestamps in 16.7 ms steps |
| Desktop app live preview (WebCodecs) | ✅ 29–30 fps at 1080p30, including after reconnects and lens switches |

## Connection & security

| Check | Result |
|:--|:--|
| TLS 1.3 handshake with certificates on both sides | ✅ |
| First pairing: same code on phone and PC, approval on phone | ✅ |
| Reconnect after pairing: no prompt, no code | ✅ |
| Discovery over Wi-Fi (subnet broadcast) | ✅ |
| Fallback to last known address (USB forward) | ✅ |

## Robustness

| Check | Result |
|:--|:--|
| Camera taken by another app, then released | ✅ Reopened automatically after 2 s |
| Camera HAL thermal shutdown (observed after ~35 min at 60 fps while charging) | ✅ Root cause found; protection added (see below) |
| Preview after reconnect / lens change | ✅ Fixed (it used to stay black) |
| Front camera orientation | ✅ Fixed (was upside down); rotation derived from the camera's own transform |
| Rotating the phone in Auto mode | ✅ Confirmed by the user in all positions |
| OBS running as administrator | ✅ Root cause found (per-user registration invisible to elevated apps); one-click system registration added and used |

## Automated tests

| Suite | Result |
|:--|:--|
| Rust (`phonecam_core`) | ✅ 8 / 8 |
| Android JVM (rotation) | ✅ 4 / 4 |
| Native filter (COM lifetime ×1000, formats, pixels) | ✅ Pass |
| Svelte type check | ✅ 0 errors |

## ❌ Not verified yet

- Automatic step-down to 1080p30 at thermal status SEVERE: the code path is in place, but it was not triggered on purpose.
- HEVC end to end in OBS.
- Streaming for hours at 1080p30.
- Other phone models: the rotation logic reads the framework transform to stay portable, but only one phone was tested.
- The minified release APK: it builds (2 MB), but was not run on the phone yet.
