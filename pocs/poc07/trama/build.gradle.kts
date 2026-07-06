plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKmpLibrary)
}

// poc-07 — backend TRAMA portado para KMP (task 3.2). Motor (Noise XX + RPC de frames +
// membership/push) em `commonMain`, indiferente à plataforma. As duas dependências de sistema
// que o Native não tem foram trocadas pelo caminho decidido nos spikes: crypto →
// cryptography-kotlin (2.2), socket → ktor-network (2.3). Threads/`CompletableFuture` do JVM
// viram coroutines; `ConcurrentHashMap`/`ByteBuffer` viram locks do atomicfu/helpers do :api.
kotlin {
    jvm()
    androidLibrary {
        namespace = "org.opentoons.poc7.trama"
        compileSdk = 36
        minSdk = 26
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        // framework estático que o app leitor iOS linka; exporta o :api (tipos do SPI + verify).
        target.binaries.framework {
            baseName = "OpenToonsKit"
            isStatic = true
            export(project(":pocs:poc07:api"))
        }
    }
    // poc-07 célula 2 (E2E) — cinterop C-ABI ao .a rust-libp2p (DEVICE). O `Libp2pBackend` fica
    // em iosArm64Main (não no iosMain compartilhado) p/ não acionar o commonizer; package fixado.
    iosArm64 {
        compilations.getByName("main").cinterops.create("poc07lp") {
            defFile(project.file("../libp2p-facade/poc07lp-device.def"))
            includeDirs(project.file("../libp2p-facade"))
        }
        // poc-07 célula 3 (E2E I2P) — cinterop C-ABI ao libi2pd embarcado (api.h + Streaming),
        // combinado em libpoc07i2p.a. OpenSSL/Boost/libz/libc++ são linkados no app (reader-ios).
        compilations.getByName("main").cinterops.create("poc07i2p") {
            defFile(project.file("../i2p-facade/poc07i2p-device.def"))
            includeDirs(project.file("../i2p-facade"))
        }
    }
    // poc-07 task 5.3 — MESMO cinterop C-ABI, agora no alvo SIMULADOR: para montar a topologia
    // TCK libp2p COMPLETA in-process (bootstrap+publicador+cliente como full nodes libp2p reais)
    // e rodar os cenários de conformidade no alvo Native (D-rules: simulador serve ao portão TCK).
    iosSimulatorArm64 {
        compilations.getByName("main").cinterops.create("poc07lp") {
            defFile(project.file("../libp2p-facade/poc07lp-sim.def"))
            includeDirs(project.file("../libp2p-facade"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pocs:poc07:api"))
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            implementation("io.ktor:ktor-network:3.5.1")
            implementation("org.jetbrains.kotlinx:atomicfu:0.33.0")
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.serialization.cbor)
        }
        jvmMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
            implementation(libs.bouncycastle.bcprov)
        }
        androidMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
            implementation(libs.bouncycastle.bcprov)
        }
        iosMain.dependencies {
            implementation("dev.whyoleg.cryptography:cryptography-provider-cryptokit:0.6.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}

// poc-07 task 5.3 — o TCK libp2p sobe 3 full nodes libp2p REAIS in-process e resolve por
// Kademlia sobre loopback. O runner padrão do KMP usa `simctl spawn --standalone`, cujo sandbox
// restringe a rede do simulador e trava a resolução da DHT. Rodar no simulador BOOTADO
// (standalone=false) dá à topologia libp2p o loopback real que ela precisa.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    standalone.set(false)
}
