package org.opentoons.poc7.probe

import kotlin.test.Test
import kotlin.test.assertFalse

/** Aferição do spike no host (provider JDK): valida a API e os vetores antes do device. */
class CryptoSpikeHostTest {
    @Test
    fun allPrimitivesPassOnHost() {
        val report = CryptoSpike.run()
        println(report)
        assertFalse(report.contains("FAIL"), "spike de crypto falhou no host:\n$report")
    }
}
