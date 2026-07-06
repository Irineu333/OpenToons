import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
    // Entry point usa FileKit.init diretamente (storage próprio + diálogos no desktop).
    implementation(libs.filekit.core)
}

compose.desktop {
    application {
        mainClass = "com.neoutils.opentoons.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.neoutils.opentoons"
            packageVersion = "1.0.0"
        }
    }
}