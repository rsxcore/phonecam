# 🤝 Project Handoff — PhoneCam 1.1

[← Back to README](README.md)

**Start here:** [README.md](README.md), [DEVELOPMENT.md](DEVELOPMENT.md) and [TEST_REPORT.md](TEST_REPORT.md).
The original version of the project is preserved in Git commit `f8a68ba`.

## What changed in 1.1

Previously, the Windows server only drew test bars, Android read a single Y plane as if it were a full NV21 frame, and the COM filter had memory-ownership and lifetime bugs. These parts are fixed. Added: a real network receiver, a Win32 client that bundles all components, a release APK, tests and documentation.

## Essentials

| | |
|:--|:--|
| Build | `pwsh -File build.ps1 -Test` |
| Never modify or publish | `android/phonecam-release.jks`, `android/signing.properties` |

> [!IMPORTANT]
> **Honest limitation:** no physical Android device was connected. CameraX and power consumption on a real phone are untested; passing JVM tests and a successful build do not prove them. Transport is still MJPEG, not H.264; there is no microphone.
