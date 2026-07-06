plugins {
    // AGP 9 traz Kotlin EMBUTIDO — aplicar `org.jetbrains.kotlin.android` conflita
    // (AgpWithBuiltInKotlinAppliedCheck). O `.kt` do app é compilado pelo próprio AGP.
    alias(libs.plugins.androidApplication)
}

// poc-07 — app leitor Android (baseline 4.2): roda o MESMO ReaderProbe de commonMain no Moto
// g30 (Kotlin/JVM/ART), provando que mover a Trama para commonMain não quebrou o caminho
// JVM/Android já validado nas POCs anteriores. Sem branch de app — só a fábrica da Trama.
android {
    namespace = "org.opentoons.poc7.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.opentoons.poc7.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-poc7"
    }
    buildTypes { release { isMinifyEnabled = false } }
    packaging {
        resources {
            excludes += setOf(
                "org/bouncycastle/pqc/**",
                "org/bouncycastle/x509/*.properties",
                "org/bouncycastle/pkix/*.properties",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

dependencies {
    implementation(project(":pocs:poc07:trama"))
    implementation(libs.kotlinx.coroutinesCore)
}
