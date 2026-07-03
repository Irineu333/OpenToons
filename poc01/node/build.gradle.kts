plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// Módulo DESCARTÁVEL da PoC (Marco 0) — ver openspec/changes/poc-01 e docs/poc01-report.md.
// Dependências pesadas (nabu/Netty) ficam confinadas aqui; nada de código de produto.

kotlin {
    jvmToolchain(21)
}

dependencies {
    // v0.8.0: última versão com GET_PROVIDERS funcional (o commit 0b421b9427,
    // usado no diagnóstico QUIC/Amino, regrediu a resposta de providers —
    // registrado no relatório). O diagnóstico QUIC está preservado no relatório.
    implementation("com.github.Peergos:nabu:v0.8.0")
    implementation("org.slf4j:slf4j-simple:2.0.13")

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

application {
    // -PpocMain=org.opentoons.poc.node.FetchClientKt para rodar outros mains da PoC
    mainClass.set(providers.gradleProperty("pocMain").getOrElse("org.opentoons.poc.node.MainKt"))
}
