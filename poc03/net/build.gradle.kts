plugins {
    alias(libs.plugins.kotlinJvm)
}

// Módulo DESCARTÁVEL da PoC poc-03 — cola KMP/Kotlin do lado do app: a superfície FFI
// (o contrato que os dois facades nativos implementam) e a verificação Ed25519/hash que
// NÃO cruza a fronteira (design D7). Os facades nativos vivem em poc03/go-facade e
// poc03/rust-facade e são cross-compilados por gomobile/cargo-ndk (fora do Gradle).
// Ver openspec/changes/poc-03 e docs/poc03-report.md.

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Ed25519 via BouncyCastle: mesmo mecanismo do poc-01/02 (D7), verificação no app —
    // funciona no Android, onde o provider Ed25519 do JDK não existe.
    api(libs.bouncycastle.bcprov)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
