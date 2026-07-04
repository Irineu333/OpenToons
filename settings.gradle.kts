rootProject.name = "OpenToons"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JitPack para o nabu e suas dependências transitivas (com.github.*);
        // usado só pelo módulo descartável poc01/
        maven("https://jitpack.io") {
            content {
                includeGroupAndSubgroups("com.github")
            }
        }
        // noise-java (dependência do jvm-libp2p) só existe no repo da Consensys
        maven("https://artifacts.consensys.net/public/maven/maven/") {
            content {
                includeGroupAndSubgroups("tech.pegasys")
            }
        }
        // .aar local do gomobile (E1a): consumido pelo app go descartável poc03/android-go
        flatDir { dirs("poc03/go-facade") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":desktopApp")
include(":shared")
include(":poc01:node")
include(":poc01:android")
include(":poc02:net")
include(":poc02:android")
// poc-03: só a cola KMP/Kotlin (superfície FFI + verificação Ed25519 do lado do app, D7).
// Os facades nativos (poc03/go-facade, poc03/rust-facade) são cross-compilados por
// gomobile/cargo-ndk fora do Gradle — não são subprojetos. O app carrega os artefatos
// (.so + binding Kotlin do UniFFI) copiados para poc03/android.
include(":poc03:net")
include(":poc03:android")
include(":poc03:android-go")
// poc-04: seam P2P com backend trocável (Trama × rust-libp2p). O rust-facade estendido
// (poc04/rust-facade, cópia do poc-03 + lado servidor) é cross-compilado por cargo-ndk
// (Android .so) e cargo (host .dylib) fora do Gradle, como no poc-03.
include(":poc04:api")
include(":poc04:trama")
include(":poc04:libp2p")
include(":poc04:android")
include(":poc04:node")
// poc-05: modo anônimo (publicador sobre Tor), backend trocável. Estende o seam do poc-04
// com `push` (o publicador não-discável não pode ser puxado) + config de anonimato como
// fábrica de backend. api/trama/node são JVM; libp2p/rust-facade/android herdam o toolchain
// nativo do poc-04. Ver openspec/changes/poc-05 e docs/poc05-report.md.
include(":poc05:api")
include(":poc05:trama")
include(":poc05:libp2p")
include(":poc05:node")
include(":poc05:android")