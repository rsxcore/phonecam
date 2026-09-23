# 🧪 Test Report — PhoneCam 1.1

[← Back to README](README.md)

**Date:** September 21, 2026  **Environment:** Windows 11 x64 (build machine)

Every check below was actually run. Physical-phone results are **not** presented as confirmed.

## Results

| Check | Result |
|:--|:--|
| Rust release build, Clippy `-D warnings` | ✅ Pass |
| Rust unit tests | ✅ 6 / 6 |
| C++ virtual camera and Win32 EXE | ✅ Release build, static CRT |
| COM lifetime / format native test | ✅ 1000 cycles, no errors |
| Android release with R8 and resource shrinking | ✅ Pass |
| Android JVM tests | ✅ 6 / 6, including real HTTP/UDP and port reuse on restart |
| Android lint (release) | ✅ 0 errors; remaining warnings are about newer versions and style preferences |
| APK | ✅ v2 signature verified by `apksigner`; package `com.phonecam`, version 1.1 (2), minSdk 24, targetSdk 36 |
| Virtual device registration | ✅ PhoneCam enumerated by ffmpeg DirectShow, HKCU registration |
| Moving test signal and window | ✅ Start from the button and live preview verified |
| Network JPEG → DirectShow | ✅ 60 frames in 2 seconds, correct colors |
| Rotation | ✅ 0°, 90°, 180°, 270° — image corner checks pass |
| Aspect ratio | ✅ Portrait letterbox and 640×480 output verified |
| Second writer | ✅ Rejected, first stream unaffected |
| Receiver stop | ✅ Stale frame replaced with a "no signal" fill |
| Restart while shared memory is held | ✅ Pass |
| Hang / disconnect / invalid JPEG length | ✅ Auto-recovery, bad frames never output |
| Continuous graph, 90 seconds | ✅ 2700 frames, 0 tears detected |
| Receiver restart within the same graph | ✅ Pass, capture not recreated |
| Network drop within the same graph | ✅ Pass, capture not recreated |

### About the soak test

In the 90-second test the source alternated between 16 solid-color 1280×720 JPEGs. The check compared colors across the whole frame after DirectShow capture; a mix of parts from different frames would be detected as a tear. This is a targeted synchronization test, not proof that no video defect of any kind can occur.

### Performance

Last receiver reading during the long test: **30.1 incoming fps; 3.3 ms decode + frame preparation.**

> [!WARNING]
> This is a synthetic JPEG on localhost on one specific PC. Camera time, JPEG encoding on the phone, Wi-Fi and the calling app's rendering are not included. These 3.3 ms must not be called end-to-end latency.

The full build and core checks are reproducible with `build.ps1 -Test`. Integration tests live in `tests/`. The last Win32 change after the network tests touched only restoring the window from the tray, with no changes to the receiver or the DLL.

## ❌ Not tested

- Physical Android camera — `adb` found no connected devices
- Behavior on a specific phone with the screen off, OEM restrictions, temperature and battery drain
- Real end-to-end latency and stability on the user's home Wi-Fi
- Real USB forwarding with a phone
- Each specific calling app, Windows Camera / Media Foundation-only apps
- Hardware H.264, microphone and x86 — not part of this implementation

> [!NOTE]
> During later UI testing, a Windows Firewall prompt appeared for the Java runtime used by the local JVM tests; security settings were not changed. Therefore the final mouse-driven discover / enter-code flow is not claimed as fully verified — its network components were tested separately by automated tests. The test button and window preview were verified visually earlier.
