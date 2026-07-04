package org.opentoons.poc4.libp2p

import org.opentoons.poc4.api.AnnounceTuning
import org.opentoons.poc4.api.BootstrapAddr
import org.opentoons.poc4.api.FullNode
import org.opentoons.poc4.api.NodeKeys
import org.opentoons.poc4.api.P2pBackend

/**
 * PoC poc-04 — fábrica do backend RUST-LIBP2P (composition root do lado libp2p). Único
 * símbolo referenciado pelos pontos de composição; todo o resto entra pelo seam do `:api`.
 */
object Libp2pBackend {

    fun client(): P2pBackend = Libp2pClient()

    fun fullNode(identitySeed: String, tuning: AnnounceTuning = AnnounceTuning()): FullNode =
        Libp2pFullNode(NodeKeys.fromSeed(identitySeed), tuning)
}

/** BootstrapAddr neutro → multiaddr com /p2p/ (PeerId derivado da MESMA chave Ed25519). */
internal fun multiaddrOf(addr: BootstrapAddr): String {
    val peerId = uniffi.facade.peerIdFromEd25519(addr.publicKeyHex)
    val hostProto = if (addr.host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) "ip4" else "dns4"
    return "/$hostProto/${addr.host}/tcp/${addr.port}/p2p/$peerId"
}
