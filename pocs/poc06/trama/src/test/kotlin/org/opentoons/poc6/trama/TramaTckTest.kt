package org.opentoons.poc6.trama

import org.opentoons.poc6.api.AnnounceTuning
import org.opentoons.poc6.api.FullNode
import org.opentoons.poc6.api.P2pBackend
import org.opentoons.poc6.api.tck.BackendHarness
import org.opentoons.poc6.api.tck.P2pConformanceTck
import org.opentoons.poc6.trama.wire.TcpTransport

/**
 * poc-06 — o PORTAO (design D3): o TCK do `:api` sobre o backend TRAMA em transporte TCP
 * loopback (cenario controlado, host unico). Prova a CORRECAO do instrumento — push/fetch
 * assinado, rejeicao de chave errada, descoberta transitiva — ANTES de qualquer medicao
 * sobre I2P real. Enquanto este suite nao esta verde, nenhum numero da campanha vale. Os
 * cenarios e vetores vem todos de P2pConformanceTck (D4); este arquivo so fornece o harness.
 * A prova sobre STREAMS I2P REAIS e o `I2pTckRigMain` do :node (portao sobre o router local).
 */
class TramaTckTest : P2pConformanceTck() {

    override fun harness(): BackendHarness = object : BackendHarness {
        override fun newFullNode(identitySeed: String, tuning: AnnounceTuning): FullNode =
            TramaBackend.fullNode(identitySeed, tuning, TcpTransport(0))

        override fun newClient(): P2pBackend =
            TramaBackend.client(TcpTransport(0), anonymous = false)
    }
}
