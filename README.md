<div align="center">

# 📱 PhoneCam

**Turn your Android phone into a Windows webcam — over local Wi-Fi.**

A **100% free, open-source alternative to DroidCam**.
No ads, no "Pro" upgrade, no paywalled HD, no watermark, no account, no cloud.

![Free](https://img.shields.io/badge/price-free%20forever-brightgreen)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
![No ads](https://img.shields.io/badge/ads-none-success)
![Version](https://img.shields.io/badge/version-1.1-2ea44f)
![Windows](https://img.shields.io/badge/Windows-10%20%7C%2011%20x64-0078D6?logo=windows&logoColor=white)
![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white)
![Rust](https://img.shields.io/badge/receiver-Rust-DEA584?logo=rust&logoColor=white)
![Kotlin](https://img.shields.io/badge/app-Kotlin-7F52FF?logo=kotlin&logoColor=white)

[Why PhoneCam](#-why-phonecam) · [Quick start](#-quick-start) · [Usage](#-usage) · [Troubleshooting](#-troubleshooting) · [Limitations](#-verification--limitations) · [Development](DEVELOPMENT.md)

</div>

---

## 💚 Why PhoneCam

Most phone-as-webcam apps are "free" until you want what actually matters: HD video, no watermark, no ads. PhoneCam is different — **every feature is free, forever, for everyone.**

| | PhoneCam |
|:--|:--:|
| 💸 Price | **Free** — no trial, no subscription, no in-app purchases |
| 📺 720p HD | **Included** — not locked behind a "Pro" version |
| 🚫 Ads | **None** — not in the app, not on the PC |
| 💧 Watermark | **None** |
| 👤 Account / sign-up | **Not needed** |
| ☁️ Cloud / telemetry | **None** — video never leaves your local network |
| 🔓 Source code | **Open source, MIT-licensed** — read it, build it, fork it |
| 🪶 Install footprint | One ~800 KB EXE + one ~800 KB APK, no drivers, no admin rights |

No tricks, no upsell, no bloat — just your phone camera on your PC.

## ✨ Features

- 🎥 **Real virtual camera** — shows up as **PhoneCam** in OBS, Discord, browsers and other DirectShow apps
- 📶 **Local Wi-Fi only** — auto-discovery on the LAN, or enter the address manually
- 🔢 **Pairing code** — a fresh six-digit code on every start blocks casual access to the stream
- 🪶 **Lightweight** — single self-contained EXE, no installer, no admin rights, no Python / .NET / Java
- 🔄 **Resilient** — automatic reconnect, and a dark "no signal" frame instead of a frozen face
- 🌐 **Browser viewer** — watch the stream straight from the phone in any browser
- 🔋 **Screen-off streaming** — a foreground service keeps running with the phone screen off

> [!NOTE]
> Video only — the microphone is not streamed.

## 🚀 Quick start

| # | Where | What to do |
|:-:|:--|:--|
| 1 | 📱 Phone | Install `PhoneCam.apk` (Android 7.0+). If an old debug build of PhoneCam is installed, uninstall it first — the new APK uses a different, permanent release signature. |
| 2 | 📶 Network | Connect the phone and the PC to the **same regular Wi-Fi network**. Guest networks often block device-to-device traffic. |
| 3 | 📱 Phone | Open PhoneCam, choose the front or back camera, tap **Start camera** and allow camera access. Notifications are recommended but optional. |
| 4 | 💻 PC | Double-click `PhoneCam.exe`. |
| 5 | 💻 PC | Click **Find phone**. If discovery fails, type the address shown on the phone, e.g. `192.168.1.42:8080`. |
| 6 | 💻 PC | Enter the six-digit code from the phone and click **Connect**. The picture and the `LIVE` status should appear. |
| 7 | 🎬 App | In OBS, Discord, a browser or another app, select the **PhoneCam** video device. Restart the app if it was open before the camera was registered. |

> [!TIP]
> Prebuilt `PhoneCam.exe` and `PhoneCam.apk` are distributed separately from the source (see Releases). To build them yourself, see [DEVELOPMENT.md](DEVELOPMENT.md).

## 🎛 Usage

**Quality presets**

| Preset | Requested resolution | Frame rate | JPEG quality | Notes |
|:--|:--|:--|:--|:--|
| **Balanced** | 1280×720 | up to 30 fps | 75 | Default |
| **Saver** | 640×360 | up to 15 fps | 65 | Less heat and traffic |
| **Detail** | 1280×720 | up to 30 fps | 90 | More traffic |

Actual resolution depends on the phone camera.

**Tips**

- 🔄 **Orientation** — hold the phone sideways for a wide frame. Portrait video keeps its aspect ratio with side bars. Text is not mirrored.
- 🔁 **Changing camera or preset** — stop streaming on the phone first. A new start generates a new code; enter it on the PC.
- 🗂 **To tray** hides the Windows window while streaming continues. Click the tray icon to reopen, or exit from its context menu. Closing the window with ✕ stops the receiver.
- 🌙 **Screen off** — the foreground service keeps streaming. Some firmwares restrict background cameras; if that happens, keep the app open and check battery restrictions for PhoneCam.
- 📡 **Network drops** — the PC retries every second after a network timeout. After 2.5 s without a fresh frame, the camera shows a dark fill instead of a frozen image.
- 🧪 **Test camera** on the PC outputs moving color bars without a phone — handy for checking the Windows side and camera registration.
- 📊 `fps` counts incoming frames; `ms decode` is decoding + frame preparation on the PC. This is **not** end-to-end lens-to-call latency.

### 🌐 Watch in a browser

On the phone, tap **Copy browser link** and open it on the PC, e.g. `http://192.168.1.42:8080/?code=123456`. This is a direct view from the phone, without the virtual camera. Every viewer adds network load, and the code changes after the phone restarts streaming.

### 🔌 USB fallback (for developers)

If Wi-Fi is unstable, you can forward the port over USB with Android platform-tools:

```powershell
adb forward tcp:8080 tcp:8080      # enable forwarding
# start streaming on the phone, then connect the PC client to 127.0.0.1:8080 with the phone's code
adb forward --remove tcp:8080      # remove forwarding
```

USB debugging must be enabled and the PC trusted on the phone. ADB is not bundled with the EXE.

> [!WARNING]
> This mode is documented from how port forwarding works; a physical USB path has not been tested.

## 🛠 Troubleshooting

| Symptom | What to check |
|:--|:--|
| Phone not found | Streaming is on; same Wi-Fi network; no client isolation or VPN. Enter the IP manually — UDP discovery is optional. |
| `403` / connection code | Use the phone's **current** code. It changes every time the service restarts. |
| `Reconnecting` | Address, Wi-Fi, streaming is running, and whether the stream opens in a browser. |
| Camera missing from the list | Run the EXE as a normal user and restart your calling app. 64-bit DirectShow apps are supported. |
| Dark frame | The receiver is off, or there has been no fresh video for more than 2.5 s. Click **Test camera** to check the Windows side. |
| Low fps / phone heating up | Choose **Saver**, close extra browser viewers, try 5 GHz Wi-Fi close to the router. |
| APK won't update the old version | The old debug APK is signed with a different key. Uninstall it and install the release APK. |
| Android asks for install permission | The permission is granted to the app you open the APK with. The APK is installed manually, not from a store. |

## ✅ Verification & limitations

**Verified.** The Windows path was tested with real DirectShow capture through ffmpeg and a network test source: rotation, colors, aspect ratio, network drops, corrupted data, restarts and COM lifetime. Android: release build, APK signature, lint, and JVM unit tests for YUV packing and a real HTTP/UDP server. Full results are in [TEST_REPORT.md](TEST_REPORT.md).

> [!IMPORTANT]
> **No physical Android phone was available during testing.** Real phone camera capture, battery drain, latency on your Wi-Fi, screen-off behavior on your firmware, and compatibility with a particular calling app are **not** confirmed. A successful build does not prove them.

**Known limitations**

- Transport is **MJPEG over HTTP**, not hardware H.264 — simple and compact with bounded queues, but more traffic and phone load than well-tuned hardware H.264.
- Virtual output is RGB32 at 30 fps; 1080p output upscales the input and does not add detail.
- Not implemented: microphone, 32-bit DLL, autostart with Windows, cloud access.
- Not every app supports DirectShow; Windows Camera and Media Foundation-only clients are not guaranteed to work.

> [!CAUTION]
> Video and the code travel over **unencrypted local HTTP**. The code prevents casual access but is not a secure transport. Use a trusted local network and never expose port 8080 to the internet.

## 📂 Files & uninstall

| What | Where |
|:--|:--|
| Unpacked components | `%LOCALAPPDATA%\PhoneCam\1.1-<hash>` — each build gets its own folder, so updates never overwrite a DLL in use |
| Settings | `%LOCALAPPDATA%\PhoneCam\settings.ini` |
| Current status | `status.txt` in the build folder |
| Recorded video | None — nothing is written to disk |

The camera is registered **per user**, without UAC. To uninstall:

1. Close all apps using the camera.
2. Run `PhoneCam.exe --uninstall`.
3. Delete the EXE and the `%LOCALAPPDATA%\PhoneCam` folder.

> [!NOTE]
> The original pre-1.1 project registered its DLL system-wide. That registration is not removed automatically and may reappear after uninstalling the new version; remove it with `regsvr32 /u` on the old DLL as administrator.

## 📚 Documentation

| Document | Contents |
|:--|:--|
| [DEVELOPMENT.md](DEVELOPMENT.md) | Architecture, protocols, build and test instructions |
| [TEST_REPORT.md](TEST_REPORT.md) | What was tested and what was not |
| [ROADMAP.md](ROADMAP.md) | Shipped in 1.1 and possible next steps |
| [HANDOFF.md](HANDOFF.md) | Project handoff notes |
| [THIRD_PARTY.md](THIRD_PARTY.md) | Third-party components and licenses |

## 📄 License

PhoneCam is open source under the [MIT License](LICENSE). Third-party components keep their own licenses — see [THIRD_PARTY.md](THIRD_PARTY.md).
