plugins {
    // AGP 9 traz Kotlin embutido (ver nota da poc07). O compilador Compose entra pelo
    // plugin standalone `org.jetbrains.kotlin.plugin.compose` — é plugin de compilador,
    // não o plugin Kotlin, então não colide com o built-in do AGP.
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "com.neoutils.opentoons.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neoutils.opentoons"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)

    // Entry point: FileKit.init(activity) para diálogos + storage próprio (D3/D4).
    implementation(libs.filekit.core)
}
