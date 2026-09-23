import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.phonecam"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.phonecam"
        minSdk = 29
        targetSdk = 36
        versionCode = 3
        versionName = "2.0"
    }

    val signingFile = rootProject.file("signing.properties")
    if (signingFile.exists()) {
        val signing = Properties().apply { signingFile.inputStream().use { load(it) } }
        signingConfigs.create("localRelease") {
            storeFile = rootProject.file(signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Same key as release so debug builds install over the user's copy.
        debug {
            signingConfig = signingConfigs.findByName("localRelease") ?: signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.findByName("localRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  testImplementation("junit:junit:4.13.2")
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.compose.bom))
  implementation(libs.compose.ui)
  implementation(libs.compose.foundation)
  implementation(libs.compose.material3)
  implementation(libs.compose.material.icons)
}
