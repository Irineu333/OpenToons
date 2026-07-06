import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKmpLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvm()

    // Marco 1 (offline-reader, task 1.1): alvos iOS adicionados ao shared. O leitor consome
    // `shared` como framework estático; BundledSQLiteDriver dispensa link de libsqlite do sistema.
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    // task 1.2: alvo Android do leitor via plugin KMP-library da AGP.
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    androidLibrary {
        namespace = "com.neoutils.opentoons.shared"
        compileSdk = 36
        minSdk = 26
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)

            implementation(libs.kotlinx.coroutinesCore)

            // Persistência offline (D8)
            implementation(libs.room.runtime)
            implementation(libs.sqlite.bundled)

            // Render (D7). NOTA (spike 2.1 / Risco nº 1): a integração do Telephoto ficou
            // bloqueada por skew de coordenadas Compose (androidx↔jetbrains) no CMP 1.11.1 —
            // ver design D7. Adotado o fallback documentado: zoom manual (paginado) + downscale
            // do Coil ao viewport (long strip), sem tiling. Telephoto = follow-up do spike.
            implementation(libs.coil.compose)
            implementation(libs.coil.core)

            // Import: picker + storage próprio (D3/D4) e descompactação (D5)
            implementation(libs.filekit.core)
            implementation(libs.filekit.dialogs.compose)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesCore)
        }
    }
}

// task 4.x — Room KSP por alvo (KMP): o mesmo compiler roda em cada plataforma.
dependencies {
    add("kspJvm", libs.room.compiler)
    add("kspAndroid", libs.room.compiler)
    add("kspIosArm64", libs.room.compiler)
    add("kspIosSimulatorArm64", libs.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

