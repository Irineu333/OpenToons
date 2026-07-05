package org.opentoons.poc6.api

/**
 * PoC poc-05 — tipos 100% NEUTROS do seam, herdados do poc-04 (design D1: reusar o seam,
 * estender, não redesenhar) e acrescidos do eixo ANÔNIMO: [AnonymityConfig] (config de
 * fábrica, D2) e a capability [Capability.ANONYMOUS_DIAL]. Nenhum conceito de backend
 * aparece aqui: nada de CID/multiaddr/PeerId (libp2p) nem de digest/formato de gossip
 * (Trama), nem de SOCKS/onion. Conversões e o "como" do circuito vivem dentro dos adapters.
 */

/** Identificador de obra — a unidade de anúncio/descoberta (Q3 do poc-02: por obra). */
@JvmInline
value class ObraId(val value: String) {
    override fun toString(): String = value
}

/**
 * Endereço de conteúdo NEUTRO: sha-256 hex dos bytes do bloco — o denominador comum.
 * O backend libp2p converte para o seu conceito interno (chave de request) no adapter.
 */
@JvmInline
value class ContentId(val hex: String) {
    override fun toString(): String = hex

    companion object {
        fun of(bytes: ByteArray): ContentId = ContentId(sha256Hex(bytes))
    }
}

/** Bloco de conteúdo endereçado por hash. */
class Block(val id: ContentId, val bytes: ByteArray) {
    companion object {
        fun of(bytes: ByteArray): Block = Block(ContentId.of(bytes), bytes)
    }
}

/**
 * Provider de uma obra: identidade + endereços OPACOS — produzidos e consumidos pelo mesmo
 * backend; o app nunca interpreta o conteúdo das strings.
 */
data class Provider(val id: String, val addresses: List<String>)

/**
 * Endereço de bootstrap neutro: host + porta + chave pública Ed25519 (hex) do nó.
 * O adapter Trama usa a chave como identidade esperada do handshake Noise; o adapter
 * libp2p deriva o conceito interno de identidade dele a partir da MESMA chave.
 */
data class BootstrapAddr(val host: String, val port: Int, val publicKeyHex: String)

/**
 * Como um full node escuta e se apresenta: porta local (0 = efêmera) e, opcionalmente,
 * o endereço público que os anúncios devem carregar (rig da PoC: IP público + port
 * forwarding, ADR-0006 — endereço público manual).
 */
data class ListenSpec(
    val port: Int,
    val publicHost: String? = null,
    val publicPort: Int? = null,
)

/**
 * Ajustes de anúncio NEUTROS: os dois motores têm TTL de provider e re-anúncio periódico
 * (gossip: epidemia com TTL; libp2p: expiração + republicação de provider records). O app
 * declara o QUE anuncia e por quanto tempo um anúncio vale; COMO o re-anúncio se propaga é
 * interno ao backend (D2 — expiry/republish nunca vazam como método).
 */
data class AnnounceTuning(
    val ttlMillis: Long = 5 * 60_000,
    val republishMillis: Long = 15_000,
)

/**
 * Capacidades divergentes entre backends (decisão da task 2.2): expostas como flag
 * CONSULTÁVEL (`Set<Capability>`) — nunca como método específico. Cada valor deste enum
 * conta como 1 ponto no inventário de vazamento do E5 (D4): a existência do flag é a
 * admissão documentada de que os backends divergem nessa capacidade.
 */
enum class Capability {
    /**
     * Atravessar NAT sem port-forward (hole-punch coordenado via relay). Presente no
     * backend libp2p (componentes DCUtR + relay client); ausente na Trama.
     */
    HOLE_PUNCH,

    /**
     * Discar por um circuito anônimo (Tor SOCKS5h / onion) sem vazar o IP de origem —
     * o eixo novo da poc-05 (D2). Presente quando o backend foi construído com uma
     * [AnonymityConfig] habilitada e sabe rotear o dial por ela. É o candidato a
     * ÚNICO ponto de vazamento novo do seam sobre os 3 do poc-04 (meta do E5, D2):
     * a existência do flag é a admissão documentada de que "discar anonimamente" é
     * uma capacidade divergente — a Trama a obtém trivialmente (Proxy SOCKS do JDK),
     * o libp2p só se o Transport custom couber no veto.
     */
    ANONYMOUS_DIAL,
}

/**
 * poc-05 (D2) — config de ANONIMATO como parâmetro de FÁBRICA de backend, nunca código de
 * app: o app/nó declara "modo anônimo on/off + endpoint do daemon"; COMO isso vira circuito
 * (SOCKS5h com resolução remota, dial por onion, contenção de swarm) é interno ao adapter.
 * Neutra por construção — não nomeia Tor nem SOCKS na assinatura pública além do endpoint
 * genérico de um proxy SOCKS5, que é o denominador comum (Tor, I2P via SAM seria variante).
 *
 * Regra de não-vazamento de DNS (D4): quando [enabled], o adapter disca por IP/onion com
 * resolução REMOTA (SOCKS5h) — nenhuma resolução de nome ocorre localmente.
 */
data class AnonymityConfig(
    val enabled: Boolean = false,
    /** Host do proxy SOCKS5 (daemon Tor local). Só usado quando [enabled]. */
    val socksHost: String? = null,
    /** Porta do proxy SOCKS5 (Tor: 9050). Só usado quando [enabled]. */
    val socksPort: Int? = null,
) {
    init {
        require(!enabled || (socksHost != null && socksPort != null)) {
            "modo anônimo habilitado exige socksHost + socksPort"
        }
    }

    companion object {
        /** Clearnet: dial direto, sem circuito. O default das POCs 01–04. */
        val DISABLED = AnonymityConfig(enabled = false)

        /** Modo anônimo via daemon Tor local (SOCKS5 em 127.0.0.1:9050 por padrão). */
        fun tor(socksHost: String = "127.0.0.1", socksPort: Int = 9050) =
            AnonymityConfig(enabled = true, socksHost = socksHost, socksPort = socksPort)
    }
}

internal fun sha256Hex(data: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }
