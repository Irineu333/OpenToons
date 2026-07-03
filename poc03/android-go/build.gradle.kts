plugins {
    alias(libs.plugins.androidApplication)
}

// Módulo DESCARTÁVEL da PoC poc-03 — app mínimo para o E1a: carregar o `.aar` do gomobile
// (go-libp2p) no dispositivo físico sem crash (tarefa 2.3). Espelha poc03/android (que carrega
// a variante rust). Ver openspec/changes/poc-03.

android {
    namespace = "org.opentoons.poc3.androidgo"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opentoons.poc3.androidgo"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc3go"
    }

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
    // .aar do gomobile (facade.Facade / facade.Node + libgojni.so), via flatDir no settings
    implementation(group = "", name = "opentoons-gofacade", ext = "aar")
    // verificação Ed25519 do lado do app (D7) reusada do poc03/net
    implementation(project(":poc03:net"))
}
