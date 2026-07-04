plugins {
    alias(libs.plugins.kotlinJvm)
    `java-test-fixtures`
}

// Módulo DESCARTÁVEL da PoC poc-04 — o SEAM (design D2): interfaces `P2pBackend` (client) e
// `FullNode` (servidor) com tipos 100% neutros, + `verify` (Ed25519 + hash) FORA do seam, +
// o TCK (em testFixtures, consumido idêntico pelos dois adapters). Kotlin puro, zero conceito
// de backend na superfície. Ver openspec/changes/poc-04 e docs/poc04-report.md.

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Ed25519 do verify (D7 do poc-03): mesmo mecanismo das POCs anteriores, fora do seam
    api(libs.bouncycastle.bcprov)

    // TCK: suíte de conformidade que qualquer backend roda idêntica (D4)
    testFixturesApi(libs.kotlin.test)
    testFixturesApi(libs.junit)
    testFixturesImplementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
