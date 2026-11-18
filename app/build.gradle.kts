plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version name comes from the release tag (APP_VERSION=v0.1.0). Version code is minutes since
// 2020 at build time, so every build (CI release or a local adb install) is an upgrade over
// the previous one. Obtainium offers an update when the tag changes; Android installs it
// only when versionCode increases.
val appVersion = (System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: "0.0.0-dev").removePrefix("v")
val appVersionCode = System.getenv("APP_VERSION_CODE")?.toIntOrNull()
    ?: ((System.currentTimeMillis() / 60_000L) - 26_297_280L).toInt() // minutes since 2020-01-01

// How much the app logs by default (the user can change it under Settings > Logs): info for stable releases,
// debug for dev builds and releases we are unsure about. scripts/build.sh sets LOG_LEVEL.
val logLevel = (System.getenv("LOG_LEVEL")?.takeIf { it.isNotBlank() } ?: "debug").lowercase()
require(logLevel in setOf("error", "warn", "info", "debug")) { "LOG_LEVEL must be error, warn, info or debug (got '$logLevel')" }

android {
    namespace = "com.kbhatiya999.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kbhatiya999.jarvis"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "LOG_LEVEL", "\"$logLevel\"")
        // The Jarvis bridge's address for Connect Gemini (scripts/build.sh sets BRIDGE_URL from fnox).
        buildConfigField("String", "BRIDGE_URL", "\"${System.getenv("BRIDGE_URL")?.trim()?.trimEnd('/') ?: ""}\"")
        // Phones only: the speech engine (Vosk) is ~10 MB per CPU type; x86 and MIPS builds would add ~25 MB.
        // Emulators on Apple Silicon Macs are ARM too.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // The same key must sign every release, otherwise Android refuses the update.
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Offline "Jarvis wake up" / "Jarvis power down" (the model is downloaded in the app when turned on).
    implementation("com.alphacephei:vosk-android:0.3.75")
}
