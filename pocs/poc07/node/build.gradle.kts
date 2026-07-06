plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

// poc-07 — nó pleno de campanha (jvm), o servidor/replicador que roda na VPS (Java 21). Reusa
// a MESMA Trama de `commonMain` (alvo jvm) que o iPhone roda em Native — mesmo código, então o
// fio é compatível por construção. `installDist` empacota com dependências para scp+java.
kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":pocs:poc07:trama"))
    implementation(libs.kotlinx.coroutinesCore)
}

application {
    mainClass.set("org.opentoons.poc7.node.VpsServerMainKt")
}
