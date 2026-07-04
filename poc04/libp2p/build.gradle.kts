plugins {
    alias(libs.plugins.kotlinJvm)
}

// Módulo DESCARTÁVEL da PoC poc-04 — backend RUST-LIBP2P: o facade rust do poc-03 (copiado e
// estendido com o lado servidor em poc04/rust-facade) atrás do seam do :poc04:api (design D3).
// O binding Kotlin do UniFFI (facade.kt) é gerado pelo build do rust-facade e copiado para cá;
// a lib nativa é .so (Android, via jniLibs do app) ou .dylib (host macOS arm64, para :poc04:node).

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":poc04:api"))
    // runtime dos bindings UniFFI; no Android o app adiciona o @aar equivalente
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation(libs.kotlinx.coroutinesCore)

    testImplementation(testFixtures(project(":poc04:api")))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

// no host (desktop JVM), o JNA resolve libuniffi_facade.dylib por jna.library.path
tasks.withType<Test> {
    systemProperty("jna.library.path", file("nativelib").absolutePath)
}
