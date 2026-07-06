plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
}

// poc-07 — a SPI de rede em `commonMain` (task 3.1, design D2). É o `:pocs:poc06:api` portado
// para KMP: MESMOS tipos e contrato, sem `java.*` na superfície. Crypto (Ed25519 + sha-256)
// via cryptography-kotlin (JDK no host/Android, CryptoKit no iOS — spike 2.2); o `ByteBuffer`
// e o `ConcurrentHashMap` do JVM viram helpers próprios + locks do atomicfu. O verify
// (`ChapterVerifier`) fica FORA do seam e roda em Kotlin/Native (D7 do poc-03).
kotlin {
    jvm()
    // AGP 9: alvo Android de KMP via o plugin novo (com.android.kotlin.multiplatform.library).
    // Moto g30 (baseline JVM/ART) — prova que a SPI portada não regride.
    androidLibrary {
        namespace = "org.opentoons.poc7.api"
        compileSdk = 36
        minSdk = 26
    }
    iosArm64()
    iosSimulatorArm64() // alvo Native onde o TCK-portão roda (D5); device é só campanha.

    sourceSets {
        commonMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serialization.cbor)
        }
        jvmMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
            // O provider JDK deriva pubkey Ed25519 da privada só COM BouncyCastle no classpath
            // (auto-detectado). No iOS, o CryptoKit deriva nativamente (Curve25519). Assim o
            // commonMain fica com uma única API (cryptography-kotlin), sem expect/actual.
            implementation(libs.bouncycastle.bcprov)
        }
        androidMain.dependencies {
            // Android/ART também usa o provider JDK + BouncyCastle (mesmo caminho do host).
            implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
            implementation(libs.bouncycastle.bcprov)
        }
        iosMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-provider-cryptokit:0.6.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
