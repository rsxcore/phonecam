# Plan: Media Foundation virtual camera

[← Back to Roadmap](../../ROADMAP.md)

**Status:** planned, not started. **Goal:** PhoneCam appears in **every** camera app on Windows 11, not only DirectShow ones.

## The problem (measured)

On the development PC (Windows 11, build 26200), enumerating capture devices with both APIs gives:

```
DirectShow:        PhoneCam, OBS Virtual Camera
Media Foundation:  (none)
```

The PhoneCam filter (`vcam/PhoneCamFilter.cpp`) is DirectShow-only, so:

| App | API it uses | Sees PhoneCam today |
|:--|:--|:--|
| OBS | DirectShow | ✅ |
| Windows Camera app | Media Foundation only | ❌ never |
| Discord, Zoom, Teams (current versions) | Media Foundation for enumeration | ❌ reported by the user |
| Chromium browsers | MF, plus DirectShow-only devices | usually ✅ |

OBS's own virtual camera is also invisible to Media Foundation, so this is a limitation of the DirectShow approach, not a PhoneCam bug.

## Approach: Windows 11 virtual camera (`MFCreateVirtualCamera`)

Windows 11 (build 22000+) lets an app register a *software camera source*. The Windows Camera Frame Server then exposes it to every Media Foundation app, and through the DirectShow bridge to DirectShow apps as well.

1. **Media source DLL** (new, in `vcam/mf/`): a COM object implementing `IMFMediaSourceEx`, `IMFMediaStream2`, `IKsControl` and `IMFGetService`, with NV12 (and RGB32 if needed) media types up to 3840×2160. smourier's open-source *VCamSample* is a good reference.
2. **Registration:** `MFCreateVirtualCamera(MFVirtualCameraType_SoftwareCameraSource, MFVirtualCameraLifetime_Session or _System, MFVirtualCameraAccess_CurrentUser, L"PhoneCam", CLSID, …)` then `Start()`. The DLL must be registered in **HKLM**, because the Frame Server loads it. Reuse the UAC helper pattern (`--register-system`).
3. **Frame transport, the hard part:** the source runs inside the **Frame Server service** (LocalService, session 0), so it can't open the current `Local\PhoneCam_Frame_v2` section in the user's session.
   - Option A: the **source** creates a `Global\` section (services hold `SeCreateGlobalPrivilege`) with a DACL granting the interactive user write access; the desktop engine opens it and writes frames as today.
   - Option B: the engine pushes frames to the source over `IKsControl` custom properties. Simpler security, more copying.
   - Keep the v2 layout (`vcam/PhoneCamProtocol.h` and `server/src/shm.rs`) so the writer side barely changes.
4. **Keep the DirectShow filter** for Windows 10 and older apps. Register both, and on Windows 11 hide the DirectShow one from apps that would then show two "PhoneCam" entries, or name them distinctly.
5. **Timestamps and pacing:** deliver samples on new-frame events and map the phone's `ptsUs` to MF time, as the DirectShow filter already does.

## Tasks

- [ ] Prototype: a source that renders a test pattern, registered with `MFCreateVirtualCamera`, visible in the Windows Camera app.
- [ ] Frame transport (option A, with option B as fallback) and the security descriptor.
- [ ] Wire it to the engine; remove the duplicate entry on Windows 11.
- [ ] Desktop app: install/repair/uninstall for the MF camera, and status in the "Virtual camera" tile.
- [ ] Tests: extend `tests/` with an MF enumeration and capture check (the probe used for the table above is ~60 lines with `MFEnumDeviceSources`).
- [ ] Docs: README compatibility table; tick the ROADMAP item.

## Done when

- PhoneCam shows live video in the Windows Camera app, Discord, Zoom, Teams and Chrome, and OBS still works.
- It survives phone reconnects (black frames or the last frame, never an error) and PC sleep/resume.
- Windows 10 keeps working through the DirectShow filter.
