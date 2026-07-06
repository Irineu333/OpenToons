package org.opentoons.poc7.trama

import org.opentoons.poc7.api.AnnounceTuning
import org.opentoons.poc7.api.FullNode
import org.opentoons.poc7.api.NodeKeys
import org.opentoons.poc7.api.P2pBackend
import org.opentoons.poc7.trama.wire.FrameTransport
import org.opentoons.poc7.trama.wire.TcpTransport

/**
 * poc-07 — fábrica do backend TRAMA (composition root) portada para KMP. Só o transporte TCP
 * clearnet nesta POC (as células 1–2 rodam clearnet; I2P/SAM é a célula 3, fora do alvo Native
 * por ora). O app/nó injeta o transporte; toda a stack (Noise, RPC, membership, push) é
 * indiferente. `tcpClient()` é o leitor do iPhone; `tcpFullNode()` é o nó de DEV/VPS.
 */
object TramaBackend {

    fun client(transport: FrameTransport, anonymous: Boolean): P2pBackend =
        TramaClient(NodeKeys.generate(), transport, anonymous)

    fun fullNode(identitySeed: String, tuning: AnnounceTuning, transport: FrameTransport): FullNode =
        TramaFullNode(NodeKeys.fromSeed(identitySeed), tuning, transport)

    fun tcpClient(): P2pBackend = client(TcpTransport(0), anonymous = false)

    fun tcpFullNode(identitySeed: String, tuning: AnnounceTuning = AnnounceTuning(), port: Int = 0): FullNode =
        fullNode(identitySeed, tuning, TcpTransport(port))
}
