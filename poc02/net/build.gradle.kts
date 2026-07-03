plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

// Módulo DESCARTÁVEL da PoC poc-02 — camada de rede própria, SEM framework P2P (design D1).
// Ver openspec/changes/poc-02 e docs/poc02-report.md. Permitidos: BouncyCastle (cripto),
// kotlinx.serialization (wire), java.net/nio (sockets). Proibidos: nabu, jvm-libp2p, Netty.

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api: os tipos de chave (Ed25519PublicKeyParameters) aparecem na API pública da camada
    api(libs.bouncycastle.bcprov)
    // bcpkix só para o E1a (geração de X.509); o overhead em APK é dado do relatório
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    // só para ler os vetores de teste oficiais do Noise (JSON); não vai para o wire
    testImplementation(libs.kotlinx.serialization.json)
    // só para a medição CBOR×protobuf da questão Q4 (RpcTest); CBOR venceu para o wire
    testImplementation(libs.kotlinx.serialization.protobuf)
}

application {
    // -PpocMain=org.opentoons.poc2.sim.SimRunnerKt etc. para os outros mains da PoC
    mainClass.set(providers.gradleProperty("pocMain").getOrElse("org.opentoons.poc2.node.MainKt"))
}
