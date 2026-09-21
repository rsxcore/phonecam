# Third-party components

PhoneCam includes third-party code through its Rust and Android dependencies. Full resolved Rust versions are recorded in server/Cargo.lock; Android direct versions are in android/gradle/libs.versions.toml.

| Component | Version | License | Project |
|---|---|---|---|
| zune-jpeg | 0.5.15 | MIT / Apache-2.0 / Zlib | https://github.com/etemesi254/zune-image |
| zune-core | 0.5.3 | MIT / Apache-2.0 / Zlib | https://github.com/etemesi254/zune-image |
| windows-rs and support crates | windows 0.58.0 | MIT / Apache-2.0 | https://github.com/microsoft/windows-rs |
| AndroidX CameraX | 1.6.2 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Core | 1.18.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Lifecycle | 2.10.0 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin and kotlinx.coroutines | resolved by Gradle | Apache-2.0 | https://github.com/JetBrains/kotlin |

Bundled license texts are in licenses/. No ffmpeg, Python, Java, Gradle, Android SDK or MSVC toolchain is included in the end-user executable or APK. Development tests use ffmpeg and Python installed on the developer machine.
