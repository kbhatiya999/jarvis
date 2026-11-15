// Jarvis logic shared by the Android and iOS apps: conversation memory, reminders, contacts,
// encrypted Jarvis-to-Jarvis messaging over the relay, the Gemini Live protocol and its tools.
// Each app supplies storage, notifications and audio through the Platform interface.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    androidTarget { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "JarvisCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
            implementation("io.ktor:ktor-client-core:3.1.3")
            implementation("io.ktor:ktor-client-websockets:3.1.3")
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
        }
        androidMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
            implementation("io.ktor:ktor-client-okhttp:3.1.3")
            implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
        }
        val androidUnitTest by getting {
            dependencies { implementation(kotlin("test-junit")) }
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.1.3")
            implementation("dev.whyoleg.cryptography:cryptography-provider-openssl3-prebuilt:0.6.0")
        }
    }
}

android {
    namespace = "com.kbhatiya999.jarvis.core"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
