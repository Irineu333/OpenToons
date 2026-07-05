package org.opentoons.poc4.libp2p

import org.opentoons.poc4.api.AnnounceTuning
import org.opentoons.poc4.api.FullNode
import org.opentoons.poc4.api.P2pBackend
import org.opentoons.poc4.api.tck.BackendHarness
import org.opentoons.poc4.api.tck.P2pConformanceTck

/**
 * PoC poc-04 — o MESMO TCK do `:api` rodando contra o backend rust-libp2p (dylib de host
 * via UniFFI/JNA). Nenhum cenário ou vetor é daqui — só o harness.
 */
class Libp2pTckTest : P2pConformanceTck() {

    override fun harness(): BackendHarness = object : BackendHarness {
        override fun newFullNode(identitySeed: String, tuning: AnnounceTuning): FullNode =
            Libp2pBackend.fullNode(identitySeed, tuning)

        override fun newClient(): P2pBackend = Libp2pBackend.client()
    }
}
