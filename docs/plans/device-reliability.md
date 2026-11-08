# Plan: stop device-specific bugs from coming back

[← Back to Roadmap](../../ROADMAP.md)

**Status:** proposed. **Why:** in one day of testing on two phones we hit a chain of bugs that each depended on the device. None was caught earlier, because PhoneCam had only been verified on one phone (Nothing Phone (1)).

| Bug | Device | Kind |
|:--|:--|:--|
| Front picture upside down | Galaxy S21+ | HAL reports an ambiguous preview transform |
| Two lenses named "Front", UI crash | Galaxy S21+ | Duplicate logical cameras |
| 60 fps freezes, camera service restarts | Galaxy S21+ (Exynos) | High-speed session advertised but not working |
| Preview shows a zoomed-in corner at 1440p | TECNO CK7n | WebView2 HEVC decoder needs the coded size |
| EV slider has no effect | any (with Lock exposure on) | HAL ignores EV while AE is locked |
| Black window | PC with RivaTuner | Third-party overlay crashes WebView2 |

## Principles

1. **Never trust advertised capabilities. Verify at runtime and fall back.** The high-speed watchdog is the model: open → check frames, size and fps within a few seconds → if wrong, remember the mode as broken for this device and lens, hide it, and drop to a safe mode.
2. **One source of truth for size and orientation.** Every consumer (virtual camera, preview, phone preview) uses the size and rotation from the CONFIG and FRAME headers, never what a decoder infers.
3. **Make the device visible.** Each phone describes itself once (lenses, sensor size, modes, extensions, encoder) so a bug report includes the facts.
4. **Test the matrix, not one path.** Lenses × modes × codecs × phones, run by a script, compared against expected numbers.

## Work items

### 1. Runtime self-checks (in the app)
- [x] High-speed modes: frames within 4 s, otherwise hide (done).
- [ ] **Every mode:** after opening, compare the measured fps (`LiveValues.fps`) and the decoded size on the PC with what was asked. Mismatch → a warning event plus the same "broken mode" path.
- [ ] **Preview vs stream:** if the WebCodecs output size differs from the CONFIG size, reconfigure once, then fall back to H.264 for the preview and log it.
- [ ] **Controls that do nothing:** after an EV/ISO/shutter change, check the capture result (`SENSOR_SENSITIVITY`, `SENSOR_EXPOSURE_TIME`, `CONTROL_AE_EXPOSURE_COMPENSATION`) and tell the user in the UI if the HAL ignored it.

### 2. Device profile and quirks
- [ ] On first connect, the phone sends a **device report**: model, SoC, Android version, every camera id with sensor size, active array, focal length, FOV, zoom range, fps ranges, high-speed sizes, extensions and encoder names. The PC saves it next to `phones.json`.
- [ ] A small **quirks table** (`android/.../camera/Quirks.kt`), keyed by manufacturer, model or SoC, for what can't be detected (e.g. "Exynos 2100: skip high-speed"). Every entry links to the log line that proved it.

### 3. Automated device test (`tests/device_matrix.py`)
Uses adb plus `PHONECAM_LOG`. Only a phone on USB and the PC app running are needed:
- [ ] For each lens × mode × codec: switch (via the CLI control channel), wait 5 s, and record fps, decoded size, preview output size (from `%TEMP%\phonecam-ui.log`), camera-service crashes (`logcat -b crash`) and CPU/GPU decode ms.
- [ ] Stop/start, Reconnect and USB unplug/replug cycles.
- [ ] Output a Markdown report per device into `TEST_REPORT.md`. A release needs a green report on at least **one Samsung (Exynos or Snapdragon), one MediaTek (TECNO/Xiaomi) and one Pixel**.

### 4. Diagnostics for users
- [ ] "Export diagnostics" in the desktop Settings: zips the engine log, the UI log, the device report and recent phone logs (if adb is present), so a remote bug comes with data.

## Done when
- A new phone runs the matrix in under 10 minutes with a readable report.
- No mode the UI offers can freeze the camera more than once per device.
- The preview and the virtual camera always show the same framing.
