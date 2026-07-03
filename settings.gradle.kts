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