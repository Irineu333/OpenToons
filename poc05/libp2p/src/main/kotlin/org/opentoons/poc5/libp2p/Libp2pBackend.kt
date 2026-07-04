package org.opentoons.poc5.libp2p

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.NodeKeys
import org.opentoons.poc5.api.P2pBackend

/**
 * PoC poc-05 — fábrica do backend RUST-LIBP2P (composition root do lado libp2p). Único
 * símbolo referenciado pelos pontos de composição; todo o resto entra pelo seam do `:api`.
 * A [AnonymityConfig] entra AQUI (D2), consumida dentro do facade rust (transporte SOCKS).
 */
object Libp2pBackend {

    /** Client. [anonymity] habilitada → publicador anônimo (transporte SOCKS-only, D6). */
    fun client(anonymity: AnonymityConfig = AnonymityConfig.DISABLED): P2pBackend = Libp2pClient(anonymity)

    fun fullNode(identitySeed: String, tuning: AnnounceTuning = AnnounceTuning()): FullNode =
        Libp2pFullNode(NodeKeys.fromSeed(identitySeed), tuning)

    /** O replicador [nodeIdHex] alcançável por onion, no formato nativo libp2p (multiaddr `/dns/…/p2p/…`). */
    fun onionProvider(nodeIdHex: String, onionHost: String, port: Int): org.opentoons.poc5.api.Provider {
        val peerId = uniffi.facade.peerIdFromEd25519(nodeIdHex)
        return org.opentoons.poc5.api.Provider(peerId, listOf("/dns/$onionHost/tcp/$port/p2p/$peerId"))
    }
}

/** BootstrapAddr neutro → multiaddr com /p2p/ (PeerId derivado da MESMA chave Ed25519). */
internal fun multiaddrOf(addr: BootstrapAddr): String {
    val peerId = uniffi.facade.peerIdFromEd25519(addr.publicKeyHex)
    val hostProto = if (addr.host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) "ip4" else "dns4"
    return "/$hostProto/${addr.host}/tcp/${addr.port}/p2p/$peerId"
}
