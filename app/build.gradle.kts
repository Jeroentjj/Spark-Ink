plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these in; local builds fall back to a dev version.
val gitSha = (System.getenv("GITHUB_SHA") ?: "dev").take(7)
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "app.sparkink"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.sparkink"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")
    }

    // Fixed public debug key so a newer APK installs over the old one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core:1.12.0") // FileProvider for handing the downloaded APK to the installer
    // On-device handwriting recognition (model is downloaded once per language).
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
}
