plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

// poc-07 — PROBE do portão 2.1 (risco #1, o skew iOS). Módulo KMP MÍNIMO cuja única razão
// de existir é provar empiricamente que um binário Kotlin/Native compilado com o toolchain
// desta bancada (Xcode 16.4 / SDK iOS 18.5) instala e EXECUTA no iPhone 11 físico (iOS 26.5).
// Não é a SPI nem a Trama: é o "hello Kotlin/Native" que de-risca todo o resto. Alvo host
// (jvm) só para aferir a régua (a mesma computação determinística dá o mesmo valor).
kotlin {
    jvm()

    iosArm64 {
        binaries.framework {
            baseName = "Probe"
            isStatic = true
        }
        // poc-07 célula 2 — cinterop C-ABI ao .a rust (DEVICE). O `Ffi` fica em source sets
        // por-alvo (iosArm64Main/iosSimulatorArm64Main), não no iosMain compartilhado, para não
        // acionar o commonizer desta versão do Kotlin; `package = poc07cffi` fixado nos .def.
        compilations.getByName("main").cinterops.create("poc07cffi") {
            defFile(project.file("../cffi-facade/poc07cffi.def"))
            includeDirs(project.file("../cffi-facade"))
        }
    }

    // alvo simulador (Kotlin/Native) — onde a EXECUÇÃO do cinterop é aferida sem o túnel do
    // device (D-rules: simulador serve à prova de mecanismo/gate, nunca a número de campo).
    iosSimulatorArm64 {
        compilations.getByName("main").cinterops.create("poc07cffi") {
            defFile(project.file("../cffi-facade/poc07cffi-sim.def"))
            includeDirs(project.file("../cffi-facade"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Spike D4 — candidato a lib de crypto cross-platform (cryptography-kotlin):
            // API uniforme sobre providers nativos. O provider é escolhido por alvo abaixo.
            implementation("dev.whyoleg.cryptography:cryptography-core:0.6.0")
            // Spike de socket (2.3) — candidato a transporte TCP cross-platform (ktor-network),
            // que roda em JVM e Kotlin/Native (posix). O outro caminho é NSStream via cinterop.
            implementation("io.ktor:ktor-network:3.5.1")
            implementation(libs.kotlinx.coroutinesCore)
        }
        jvmMain.dependencies {
            // Aferição no host: provider JDK (BouncyCastle-livre, JCA).
            implementation("dev.whyoleg.cryptography:cryptography-provider-jdk:0.6.0")
        }
        iosMain.dependencies {
            // O que o spike 2.2 mede de fato: o provider CryptoKit no iosArm64.
            implementation("dev.whyoleg.cryptography:cryptography-provider-cryptokit:0.6.0")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
