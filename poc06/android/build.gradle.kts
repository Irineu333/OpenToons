plugins {
    alias(libs.plugins.androidApplication)
}

// Modulo DESCARTAVEL da PoC poc-06 — o app leitor sobre I2P. Prova o cross-compile do adapter
// SAM (poc06:trama, Kotlin puro java.net.Socket) para Android arm64: um grep no src/main
// confirma ZERO branch de transporte app-level (o transporte entra pela fabrica i2pClient).
// O app fala SAM ao router I2P LOCAL do device (127.0.0.1:7656) — mesmo codigo do desktop.

android {
    namespace = "org.opentoons.poc6.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opentoons.poc6.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc6"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/*.properties",
                "org/bouncycastle/pkix/*.properties",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":poc06:api"))
    implementation(project(":poc06:trama"))
    implementation(libs.kotlinx.coroutinesCore)
}
