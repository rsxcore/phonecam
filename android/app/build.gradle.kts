import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
}

android {
    namespace = "com.phonecam"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.phonecam"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
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
      compose = false
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
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.service)

  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.lifecycle)
}
