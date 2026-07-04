package org.opentoons.poc4.trama

import org.opentoons.poc4.api.AnnounceTuning
import org.opentoons.poc4.api.FullNode
import org.opentoons.poc4.api.NodeKeys
import org.opentoons.poc4.api.P2pBackend

/**
 * PoC poc-04 — fábrica do backend TRAMA (composition root do lado Trama). É o ÚNICO
 * símbolo que os pontos de composição (flavor do app, mains do :node) referenciam;
 * todo o resto entra pelo seam do `:api`.
 */
object TramaBackend {

    fun client(): P2pBackend = TramaClient(NodeKeys.generate())

    fun fullNode(identitySeed: String, tuning: AnnounceTuning = AnnounceTuning()): FullNode =
        TramaFullNode(NodeKeys.fromSeed(identitySeed), tuning)
}
