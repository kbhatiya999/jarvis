plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version comes from the release tag (APP_VERSION=0.1.0) and the CI run number.
// Obtainium only offers an update when versionName/tag changes; Android only
// installs it when versionCode increases.
val appVersion = (System.getenv("APP_VERSION") ?: "0.0.0-dev").removePrefix("v")
val appVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "com.kbhatiya999.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kbhatiya999.jarvis"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersion
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
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
