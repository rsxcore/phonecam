# Third-party components

[← Back to README](README.md)

PhoneCam includes third-party code through its Rust, JavaScript and Android dependencies. 

| Component | License | Project |
|---|---|---|
| windows-rs | MIT / Apache-2.0 | https://github.com/microsoft/windows-rs |
| rustls, rustls-pki-types, rustls-webpki | Apache-2.0 / MIT / ISC | https://github.com/rustls/rustls |
| ring | ISC-style / OpenSSL / MIT | https://github.com/briansmith/ring |
| rcgen | MIT / Apache-2.0 | https://github.com/rustls/rcgen |
| serde, serde_json | MIT / Apache-2.0 | https://github.com/serde-rs |
| Tauri and plugins | MIT / Apache-2.0 | https://github.com/tauri-apps/tauri |
| Svelte | MIT | https://github.com/sveltejs/svelte |
| Lucide icons | ISC | https://github.com/lucide-icons/lucide |
| Inter, JetBrains Mono (Fontsource) | OFL-1.1 | https://fontsource.org |
| AndroidX Core, Lifecycle, Activity, Compose, Material 3 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin and kotlinx.coroutines | Apache-2.0 | https://github.com/JetBrains/kotlin |

Exact versions are pinned in `server/Cargo.lock`, `desktop/src-tauri/Cargo.lock`, `desktop/package-lock.json` and `android/gradle/libs.versions.toml`.

Bundled license texts are in licenses/. No ffmpeg, Python, Java, Node.js, Android SDK or MSVC toolchain is included in the end-user executable or APK. Development tests use ffmpeg and Python installed on the developer machine.
