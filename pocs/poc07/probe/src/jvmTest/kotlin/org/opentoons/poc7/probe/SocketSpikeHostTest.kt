package org.opentoons.poc7.probe

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Aferição do spike de socket no host: disca o echo REAL da VPS (IP público, datacenter) e
 * confere o eco. Prova a régua e a alcançabilidade antes de rodar o mesmo código no device.
 */
class SocketSpikeHostTest {
    @Test
    fun dialsVpsEcho() {
        val report = SocketSpike.run("143.95.220.165", 5599)
        println(report)
        assertTrue(report.contains("PASS"), "socket spike falhou no host: $report")
    }
}
