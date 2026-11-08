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
| Build | `powershell -ExecutionPolicy Bypass -File .\build.ps1 -Test` (Windows PowerShell 5.1 works) |
| Never modify or publish | `android/phonecam-release.jks`, `android/signing.properties` |
| JDK / SDK on the dev PC | `JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot`, SDK in `%LOCALAPPDATA%\Android\Sdk` (`android/local.properties`) |
| Phone over USB | `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe install -r dist\PhoneCam.apk`. Press Stop in the app first: killing it mid-capture once crashed a Samsung camera service |
| Logs | PC: run with `PHONECAM_LOG=path`; UI/preview errors: `%TEMP%\phonecam-ui.log`; phone: `adb logcat -s PhoneCam.Streamer PhoneCam.GL PhoneCam.Encoder PhoneCam.Server` |
| Find code | `scripts\graft-query.ps1 find/callers <symbol>` and the `graft/` cards (see `AGENTS.md`); rebuild with `scripts\graft-build.ps1` |

## Status (2026-10-26)

**Branch `feature/phone-dials-pc`** (19 commits on top of `6574ba7`):
- Fork: [crrrowz/phonecam#1](https://github.com/crrrowz/phonecam/pull/1) is **merged** into the fork's `main` up to `0579d7c`. The last 3 commits (`902da95`, `289b0bf`, `2e08dd4`: start retries, stale error banner, waiting out overheating) are **not in the fork's `main` yet**.
- Upstream: [rsxcore/phonecam#1](https://github.com/rsxcore/phonecam/pull/1) is **open** with all 19 commits.

**Done and verified on devices** (Galaxy S21+ SM-G996B Exynos, TECNO CK7n MediaTek; PC on Ethernet `192.168.100.2`):
- Phone dials the PC on port 8090 (works behind a router's NAT); PC approval for new phones; firewall button; PC address shown in the UI.
- Reconnect on both apps; the phone sends its own address in HELLO.
- Front-camera rotation (SENSOR_ORIENTATION for mirrored cameras); duplicate lenses named by FOV ("Front (narrow)").
- High-speed (60 fps) modes that never deliver frames are detected after 4 s and hidden per lens ("Show all modes" brings them back). On the S21+ every lens failed: the HAL logs `m_callbackQ wait timeout`, then `CameraServiceWatchdog` aborts the camera service.
- Camera-service restarts are retried with backoff; the counter resets when frames flow.
- Desktop: Svelte `each_key_duplicate` fix, preview `codedWidth/Height` (1440p HEVC on TECNO showed a quarter frame), narrow-window layout, RivaTuner detection plus Fix (its `RTSSHooks64.dll` crashes WebView2).
- EV now works with "Lock exposure" on (moving EV unlocks AE).

**Built but NOT yet verified on a phone** (adb could not see the TECNO afterwards):
- `902da95`, `289b0bf`, `2e08dd4`: any failed start is retried; a stale "Cannot start" banner clears once frames flow; at thermal SEVERE the phone waits to cool without a retry limit and restarts when the status drops. Trigger: the TECNO at 4K/1440p for about 10 minutes reaches thermal 3 (SEVERE), steps down to 1080p30, then lists **no video encoder** ("No video/avc encoder for 1920x1080").
- Camera2 extension support (BOKEH/HDR/NIGHT/FACE_RETOUCH) is logged as `Extensions <id> <name>: [...]`. It hasn't been read on either phone yet.

## To do next (by priority)

1. **Verify the 3 untested commits** on the TECNO: a long run at high resolution, then confirm the step-down, the "too hot" message and automatic recovery. Then merge them into the fork's `main`.
2. **Media Foundation virtual camera**: PhoneCam is invisible to the Windows Camera app, Discord and Zoom (Media Foundation lists no cameras; DirectShow lists PhoneCam and OBS). See [docs/plans/media-foundation-camera.md](docs/plans/media-foundation-camera.md).
3. **Device reliability**: runtime self-checks, device report, quirks table, adb test matrix. See [docs/plans/device-reliability.md](docs/plans/device-reliability.md).
4. **One-click USB connection**. See [docs/plans/usb-connection.md](docs/plans/usb-connection.md). `README.md` still wrongly claims USB is automatic.
5. **Portrait / background blur** via Camera2 Extensions `BOKEH` (no PC cost): read the `Extensions` log line on each phone first.
6. **Real 60 fps on Samsung Exynos**: find a session setup the HAL accepts.
7. Docs: refresh `TEST_REPORT.md` with the S21+ and TECNO results. Untracked local files that should not be committed: `phonecam-log.txt`, `hs_err_pid11700.log`, `PowerShellHistory.txt`, `scripts/PowerShellHistory.txt`.
