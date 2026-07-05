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
        // usado só pelo módulo descartável pocs/poc01/
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
        // .aar local do gomobile (E1a): consumido pelo app go descartável pocs/poc03/android-go
        flatDir { dirs("pocs/poc03/go-facade") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":desktopApp")
include(":shared")

// As PoCs vivem sob pocs/ e são subprojetos Gradle genuínos: o caminho lógico
// :pocs:pocNN:<módulo> espelha o diretório físico pocs/pocNN/<módulo>, então o Gradle
// deriva o projectDir sozinho — sem redirecionamento manual. Os containers :pocs,
// :pocs:pocNN são criados automaticamente (não têm build script próprio).
include(":pocs:poc01:node")
include(":pocs:poc01:android")
include(":pocs:poc02:net")
include(":pocs:poc02:android")
// poc-03: só a cola KMP/Kotlin (superfície FFI + verificação Ed25519 do lado do app, D7).
// Os facades nativos (pocs/poc03/go-facade, pocs/poc03/rust-facade) são cross-compilados por
// gomobile/cargo-ndk fora do Gradle — não são subprojetos. O app carrega os artefatos
// (.so + binding Kotlin do UniFFI) copiados para pocs/poc03/android.
include(":pocs:poc03:net")
include(":pocs:poc03:android")
include(":pocs:poc03:android-go")
// poc-04: seam P2P com backend trocável (Trama × rust-libp2p). O rust-facade estendido
// (pocs/poc04/rust-facade, cópia do poc-03 + lado servidor) é cross-compilado por cargo-ndk
// (Android .so) e cargo (host .dylib) fora do Gradle, como no poc-03.
include(":pocs:poc04:api")
include(":pocs:poc04:trama")
include(":pocs:poc04:libp2p")
include(":pocs:poc04:android")
include(":pocs:poc04:node")
// poc-05: modo anônimo (publicador sobre Tor), backend trocável. Estende o seam do poc-04
// com `push` (o publicador não-discável não pode ser puxado) + config de anonimato como
// fábrica de backend. api/trama/node são JVM; libp2p/rust-facade/android herdam o toolchain
// nativo do poc-04. Ver openspec/changes/poc-05 e docs/pocs/poc05-report.md.
include(":pocs:poc05:api")
include(":pocs:poc05:trama")
include(":pocs:poc05:libp2p")
include(":pocs:poc05:node")
include(":pocs:poc05:android")
// poc-06: rede nativamente anônima sobre I2P. Reusa o seam do poc-05 e troca APENAS o
// transporte: TRAMA sobre SAM v3 (STREAM CONNECT/ACCEPT por destination) atrás do mesmo
// `FrameTransport`. api/trama/node são JVM; o mesmo adapter cross-compila para Android
// (fala SAM ao router local). Ver openspec/changes/poc-06 e docs/pocs/poc06-report.md.
include(":pocs:poc06:api")
include(":pocs:poc06:trama")
include(":pocs:poc06:node")
include(":pocs:poc06:android")