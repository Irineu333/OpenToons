package org.opentoons.poc7.probe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * poc-07 célula 2 — EXECUÇÃO do cinterop C-ABI ao `.a` Rust em Kotlin/Native (roda no
 * iosSimulatorArm64). Prova que o binding NÃO é só compilável: o código Rust cross-compilado
 * EXECUTA quando chamado de Kotlin/Native e é interoperável byte-a-byte (aferição D6: o
 * sha256 devolvido pelo Rust bate com o vetor conhecido, calculado independente em Python).
 */
class FfiTest {
    @Test
    fun rustCffiExecutesAndMatchesVector() {
        val line = Ffi.run()
        println(line)
        assertTrue(line.contains("add=42"), "soma via C-ABI errada: $line")
        // sha256("opentoons-poc07") calculado independentemente (Python)
        val expected = "4054fad14f51ca5f02f7d78feaca485c879c15ebe7afd00c66c20d4c0bdd00f5"
        assertTrue(line.contains(expected), "sha256 do Rust não bate com o vetor: $line")
    }
}
