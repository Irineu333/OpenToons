plugins {
    alias(libs.plugins.androidApplication)
}

// Módulo DESCARTÁVEL da PoC poc-04 — o MESMO app nas 8 células da matriz E2E (design D6).
// Build variants `trama`/`libp2p` (dimension "backend"): EXATAMENTE UM backend por APK (D1).
// O código de src/main consome SÓ o :poc04:api; cada flavor contribui um único arquivo
// (BackendFactory) — a escolha vive inteiramente no grafo de dependências do build, 0 branch.

android {
    namespace = "org.opentoons.poc4.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opentoons.poc4.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc4"
    }

    flavorDimensions += "backend"
    productFlavors {
        create("trama") {
            dimension = "backend"
            applicationIdSuffix = ".trama"
        }
        create("libp2p") {
            dimension = "backend"
            applicationIdSuffix = ".libp2p"
        }
    }

    // as .so do rust-facade (cargo-ndk) são copiadas para cá pelo build da PoC — só o flavor
    // libp2p as usa; o flavor trama não tem jniLibs (o APK é o dado da regressão de peso Q4)
    sourceSets["libp2p"].jniLibs.srcDirs("src/libp2p/jniLibs")

    buildTypes {
        release {
            // medição de peso (task 7.2): release + R8, mesmas condições do poc-02
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        resources {
            // mesmos excludes do poc-02 (recursos PQC do bcprov que nada referencia)
            excludes += setOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/*.properties",
                "org/bouncycastle/pkix/*.properties",
            )
        }
    }

    // E5/7.2: APK por ABI para a variant libp2p (comparável ao 8–11 MB/ABI do poc-03)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // src/main só enxerga o seam
    implementation(project(":poc04:api"))
    implementation(libs.kotlinx.coroutinesCore)

    // a escolha do backend vive AQUI (grafo de build), não em código:
    "tramaImplementation"(project(":poc04:trama"))
    "libp2pImplementation"(project(":poc04:libp2p")) {
        // no Android o runtime JNA entra como @aar (abaixo); o jar JVM sai para não duplicar
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    // runtime JNA do binding UniFFI — só na variant libp2p
    "libp2pImplementation"("net.java.dev.jna:jna:5.14.0@aar")
}
