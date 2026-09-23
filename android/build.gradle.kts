// AGP 9 compiles Kotlin itself; only the Compose compiler plugin is added on top.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.compose.compiler) apply false
}
