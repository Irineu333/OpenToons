plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// Módulo DESCARTÁVEL da PoC poc-04 — full nodes de HOST (desktop JVM). O driver (NodeRunner)
// é escrito 100% contra a interface FullNode do :poc05:api — zero branch por backend. Os
// mains são composition roots: cada um referencia exatamente um backend (TramaNodeMain,
// Libp2pNodeMain) ou os dois (DualStackNodeMain — a exceção deliberada D7: a ponte de
// migração, host onde peso é livre).

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":poc05:api"))
    implementation(project(":poc05:trama"))
    implementation(project(":poc05:libp2p")) // E2: facade rust estendido com transporte Tor
    implementation(libs.kotlinx.coroutinesCore)
    // vetores/adulteração do TCK reusados pelos drivers de host (mesma obra nas 8 células)
    implementation(testFixtures(project(":poc05:api")))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

application {
    // Os mains reais são composition roots por backend; os processos da matriz E4 são
    // lançados por `java -cp build/install/node/lib/*` (ver scripts/) — um main por processo.
    mainClass.set(providers.gradleProperty("pocMain").getOrElse("org.opentoons.poc5.node.TramaMainKt"))
}

// o backend libp2p carrega o .dylib de host por JNA (mesmo mecanismo do :poc05:libp2p:test)
tasks.withType<JavaExec> {
    systemProperty("jna.library.path", project(":poc05:libp2p").file("nativelib").absolutePath)
}
