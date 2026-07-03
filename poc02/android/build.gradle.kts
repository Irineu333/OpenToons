plugins {
    alias(libs.plugins.androidApplication)
}

// Módulo DESCARTÁVEL da PoC poc-02 — app mínimo para os experimentos E1 (handshake em
// dispositivo físico), E4 (E2E) e E5 (medições). Ver openspec/changes/poc-02.

android {
    namespace = "org.opentoons.poc2.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opentoons.poc2.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc2"
    }

    buildTypes {
        release {
            // só para medir o delta de APK com R8 (task 8.3); sem assinatura
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            // recursos de algoritmos pós-quânticos do bcprov que a camada não usa (1,2 MB)
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
    implementation(project(":poc02:net"))
}
