plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// Modulo DESCARTAVEL da PoC poc-06 — drivers de host (desktop JVM) do rig I2P. Escrito 100%
// contra a interface do :pocs:poc06:api (zero branch por backend). Os mains sao composition roots
// do rig: TCK sobre I2P real, warmup/alcancabilidade (T0), backbone (T1), descoberta (T2),
// velocidade. O backend TRAMA sobre SAM v3 e o unico transporte novo do poc-06.

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":pocs:poc06:api"))
    implementation(project(":pocs:poc06:trama"))
    implementation(libs.kotlinx.coroutinesCore)
    // vetores/adulteracao do TCK reusados pelos drivers de host (mesma obra do TCK)
    implementation(testFixtures(project(":pocs:poc06:api")))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}

application {
    mainClass.set(providers.gradleProperty("pocMain").getOrElse("org.opentoons.poc6.node.I2pTckRigMainKt"))
}
