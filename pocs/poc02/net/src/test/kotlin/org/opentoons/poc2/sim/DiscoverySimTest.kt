package org.opentoons.poc2.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * E3 — correção das duas variantes nas escalas rápidas (n=10, 100); a matriz completa
 * (até n=10.000) roda via `gradlew :pocs:poc02:net:run -PpocMain=org.opentoons.poc2.sim.SimRunnerKt`.
 */
class DiscoverySimTest {

    // 5.4 (recorte rápido) — descoberta fria resolve nas duas variantes
    @Test
    fun `gossip resolve descoberta fria com churn em escalas rapidas`() {
        listOf(10, 100).forEach { n ->
            val m = runGossip(n)
            assertTrue(m.coldLookupRtts <= 3.0, "gossip n=$n: lookup frio ${m.coldLookupRtts} RTTs > 3 (limiar D5)")
            assertTrue(m.warmLookupRtts <= m.coldLookupRtts)
            assertTrue(
                m.convergenceAfterChurnSeconds in 1..SimScenario.CONVERGENCE_CAP_TICKS * TICK_SECONDS,
                "gossip n=$n não convergiu pós-churn",
            )
            // 5.5 — ADR-0005: cliente nunca aceita entrada nem armazena registros
            assertEquals(0, m.clientInboundRequests)
            assertEquals(0, m.clientStoredRecords)
        }
    }

    @Test
    fun `kademlia resolve descoberta fria com churn em escalas rapidas`() {
        listOf(10, 100).forEach { n ->
            val m = runKademlia(n)
            assertTrue(m.coldLookupRtts >= 2.0, "kademlia n=$n: walk deveria custar >= 2 RTTs")
            assertTrue(
                m.convergenceAfterChurnSeconds in 1..SimScenario.CONVERGENCE_CAP_TICKS * TICK_SECONDS,
                "kademlia n=$n não convergiu pós-churn",
            )
            assertEquals(0, m.clientInboundRequests)
            assertEquals(0, m.clientStoredRecords)
        }
    }
}
