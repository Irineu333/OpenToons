plugins {
    alias(libs.plugins.androidApplication)
}

// Módulo DESCARTÁVEL da PoC poc-03 — app mínimo para o E1 (carregar o binding no dispositivo
// físico sem crash, tarefas 2.3/3.3). Esta variante carrega o **rust-facade** (.so via UniFFI).
// A variante go (.aar do gomobile) é validada à parte. Ver openspec/changes/poc-03.

android {
    namespace = "org.opentoons.poc3.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opentoons.poc3.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc3"
    }

    // as .so do rust-facade (cargo-ndk) são copiadas para cá pelo passo de build da PoC
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")

    buildTypes {
        release { isMinifyEnabled = false }
    }

    // E5/8.3: APK por ABI (split) para medir o tamanho por ABI vs o teto de 20 MB (D5)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // runtime que os bindings Kotlin do UniFFI exigem no Android
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation(libs.kotlinx.coroutinesCore)
    // verificação Ed25519 do lado do app (D7) reusada do poc03/net
    implementation(project(":poc03:net"))
}
