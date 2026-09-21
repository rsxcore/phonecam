// Top-level build file. Only the Android application plugin is needed: AGP 9
// compiles Kotlin itself, so there is no separate Kotlin plugin to apply.
plugins {
  alias(libs.plugins.android.application) apply false
}
