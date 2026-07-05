package org.opentoons.poc5.trama

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.tck.BackendHarness
import org.opentoons.poc5.api.tck.P2pConformanceTck

/**
 * PoC poc-04 — o TCK do `:api` rodando contra o backend TRAMA. Nenhum cenário ou vetor é
 * daqui: tudo vem de P2pConformanceTck (D4) — este arquivo só fornece o harness.
 */
class TramaTckTest : P2pConformanceTck() {

    override fun harness(): BackendHarness = object : BackendHarness {
        override fun newFullNode(identitySeed: String, tuning: AnnounceTuning): FullNode =
            TramaBackend.fullNode(identitySeed, tuning)

        override fun newClient(): P2pBackend = TramaBackend.client()
    }
}
