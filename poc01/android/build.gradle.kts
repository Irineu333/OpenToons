plugins {
    alias(libs.plugins.androidApplication)
}

// Módulo DESCARTÁVEL da PoC (Marco 0) — spike E2: nabu no Android.
// Ver openspec/changes/poc-01 e docs/poc-report.md.

android {
    namespace = "org.opentoons.poc.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.opentoons.poc.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc"
    }

    packaging {
        resources {
            // Netty e dependências trazem metadados duplicados que quebram o merge
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
            pickFirsts += setOf(
                "META-INF/io.netty.versions.properties",
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        // nabu traz bcprov-jdk15on (via jvm-libp2p) e bcprov-jdk18on ao mesmo tempo;
        // os pacotes são os mesmos, então unificamos no jdk18on
        substitute(module("org.bouncycastle:bcprov-jdk15on"))
            .using(module("org.bouncycastle:bcprov-jdk18on:1.76"))
        substitute(module("org.bouncycastle:bcpkix-jdk15on"))
            .using(module("org.bouncycastle:bcpkix-jdk18on:1.76"))
    }
}

dependencies {
    implementation("com.github.Peergos:nabu:v0.8.0")
    implementation("org.slf4j:slf4j-android:1.7.36")
    // Reusa Manifest/ManifestCodec do E3 (verificação do capítulo no E4).
    // O nabu transitivo é excluído: o app fica no v0.8.0 (o commit QUIC usado
    // pelo poc/node não tem natives QUIC para Android)
    implementation(project(":poc01:node")) {
        exclude(group = "org.slf4j", module = "slf4j-simple")
        exclude(group = "com.github.Peergos", module = "nabu")
    }
}
