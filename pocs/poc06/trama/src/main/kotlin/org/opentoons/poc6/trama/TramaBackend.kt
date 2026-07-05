package org.opentoons.poc6.trama

import org.opentoons.poc6.api.AnnounceTuning
import org.opentoons.poc6.api.FullNode
import org.opentoons.poc6.api.NodeKeys
import org.opentoons.poc6.api.P2pBackend
import org.opentoons.poc6.trama.wire.FrameTransport
import org.opentoons.poc6.trama.wire.SamSession
import org.opentoons.poc6.trama.wire.SamTransport
import org.opentoons.poc6.trama.wire.TcpTransport

/**
 * poc-06 — fabrica do backend TRAMA (composition root). O eixo do poc-06 e o TRANSPORTE
 * (design D2): o mesmo backend roda clearnet ([TcpTransport], baseline/TCK) ou nativamente
 * anonimo sobre I2P ([SamTransport], falando SAM a um router local). O `:node`/app escolhe o
 * transporte; toda a stack (Noise, RPC, membership, push) e indiferente.
 */
object TramaBackend {

    // ---- transporte generico (o app injeta) ----

    fun client(transport: FrameTransport, anonymous: Boolean): P2pBackend =
        TramaClient(NodeKeys.generate(), transport, anonymous)

    fun fullNode(identitySeed: String, tuning: AnnounceTuning, transport: FrameTransport): FullNode =
        TramaFullNode(NodeKeys.fromSeed(identitySeed), tuning, transport)

    // ---- atalhos I2P/SAM (o modo nativamente anonimo do poc-06) ----

    /**
     * Client anonimo sobre I2P: abre uma sessao SAM TRANSIENTE (destination efemera, so-saida
     * do ADR-0005) e disca por destination. E o leitor mobile do plano A e o publicador.
     */
    fun i2pClient(samHost: String = "127.0.0.1", samPort: Int = 7656, nick: String): P2pBackend {
        val session = SamSession.create(samHost, samPort, nick)
        return client(SamTransport(session), anonymous = true)
    }

    /**
     * Full node sobre I2P: [destKey] persiste a destination (endereco estavel do bootstrap/R
     * entre reinicios); nulo -> transiente. A destination publica sai em [FullNode.selfProvider].
     */
    fun i2pFullNode(
        identitySeed: String,
        tuning: AnnounceTuning = AnnounceTuning(),
        samHost: String = "127.0.0.1",
        samPort: Int = 7656,
        nick: String,
        destKey: String? = null,
    ): Pair<FullNode, SamSession> {
        val session = SamSession.create(samHost, samPort, nick, destKey)
        return fullNode(identitySeed, tuning, SamTransport(session)) to session
    }

    // ---- atalhos TCP (baseline clearnet e TCK sobre loopback) ----

    fun tcpClient(): P2pBackend = client(TcpTransport(0), anonymous = false)

    fun tcpFullNode(identitySeed: String, tuning: AnnounceTuning = AnnounceTuning(), port: Int = 0): FullNode =
        fullNode(identitySeed, tuning, TcpTransport(port))
}
