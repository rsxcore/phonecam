fn main() {
    // The virtual camera DLL is embedded into the executable.
    println!("cargo:rerun-if-changed=../../vcam/out/PhoneCam.dll");
    tauri_build::build()
}
