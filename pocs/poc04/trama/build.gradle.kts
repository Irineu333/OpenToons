plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

// Módulo DESCARTÁVEL da PoC poc-04 — backend TRAMA (a stack própria do poc-02: Noise XX +
// RPC de frames + membership/gossip) refatorada para trás do seam do :pocs:poc04:api (design D3).
// Refatoração honesta de poc02/net (que fica intocado como baseline), client E full node.

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":pocs:poc04:api"))
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(testFixtures(project(":pocs:poc04:api")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
