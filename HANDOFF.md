# 🤝 Project Handoff: PhoneCam 2.0

[← Back to README](README.md)

**Start here:** [README.md](README.md), [DEVELOPMENT.md](DEVELOPMENT.md) and [TEST_REPORT.md](TEST_REPORT.md).

## What 2.0 changed

Version 1.1 streamed software-encoded MJPEG at 720p30 with a six-digit code per session. 2.0 rebuilt every part:

- **Phone:** Camera2 with manual controls, a GPU relay and hardware H.264/HEVC. The new Compose UI adds thermal protection and camera recovery.
- **Network:** protocol v2 over TLS 1.3 with one-time pairing and automatic discovery.
- **PC:** a Rust engine with GPU decoding, an NV12 DirectShow filter up to 4K, and a Tauri desktop app.

## Essentials

| | |
|:--|:--|
| Build | `pwsh -File build.ps1 -Test` |
| Never modify or publish | `android/phonecam-release.jks`, `android/signing.properties` |
| Quick phone iteration | `work/deploy.sh` (build, install over USB, relaunch, forward port) |

> [!NOTE]
> Tested on one phone (Nothing Phone (1)). See the "Not verified yet" list in [TEST_REPORT.md](TEST_REPORT.md).
