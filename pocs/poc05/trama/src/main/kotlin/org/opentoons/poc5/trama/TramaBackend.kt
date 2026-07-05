package org.opentoons.poc5.trama

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.NodeKeys
import org.opentoons.poc5.api.P2pBackend

/**
 * PoC poc-05 — fábrica do backend TRAMA (composition root do lado Trama). É o ÚNICO símbolo
 * que os pontos de composição (flavor do app, mains do :node) referenciam; todo o resto
 * entra pelo seam do `:api`. A [AnonymityConfig] entra AQUI (D2): é config de FÁBRICA, não
 * de app — o `:node`/app declara "modo anônimo on/off + endpoint"; a Trama a consome dentro
 * do adapter (Proxy SOCKS no dial). O publicador anônimo é só um `client(AnonymityConfig.tor())`.
 */
object TramaBackend {

    /** Client (só saída). [anonymity] habilitada → dial por SOCKS5h (D4) e capability ANONYMOUS_DIAL. */
    fun client(anonymity: AnonymityConfig = AnonymityConfig.DISABLED): P2pBackend =
        TramaClient(NodeKeys.generate(), anonymity)

    fun fullNode(identitySeed: String, tuning: AnnounceTuning = AnnounceTuning()): FullNode =
        TramaFullNode(NodeKeys.fromSeed(identitySeed), tuning)

    /** O replicador [nodeIdHex] alcançável por onion, no formato nativo Trama (`host:port`). */
    fun onionProvider(nodeIdHex: String, onionHost: String, port: Int): org.opentoons.poc5.api.Provider =
        org.opentoons.poc5.api.Provider(nodeIdHex, listOf("$onionHost:$port"))
}
