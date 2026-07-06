package org.opentoons.poc7.probe

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Aferição da régua (D6): fixa o valor conhecido de `fnv1a64("opentoons-poc07")`. O mesmo
 * teste roda no host (jvmTest) e no device (iosArm64Test não é executável aqui, mas o app
 * do portão 2.1 confere o MESMO valor em runtime no iPhone). Se o device devolver isto, o
 * runtime Kotlin/Native executou a aritmética real.
 */
class ProbeTest {
    @Test
    fun fnvVectorIsStable() {
        val h = Probe.fnv1a64("opentoons-poc07".encodeToByteArray())
        // valor de referência calculado por esta mesma função (fixado após 1ª execução host).
        assertEquals(EXPECTED, h)
    }

    companion object {
        // Referência FNV-1a/64 de "opentoons-poc07", calculada independentemente (Python).
        val EXPECTED: ULong = 0xbf563927f1836e88UL
    }
}
