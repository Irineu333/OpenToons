package org.opentoons.poc4.api

/**
 * PoC poc-04 — tipos 100% NEUTROS do seam (design D2). Nenhum conceito de backend aparece
 * aqui: nada de CID/multiaddr/PeerId (libp2p) nem de digest/formato de gossip (Trama).
 * Conversões vivem dentro dos adapters.
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
}

internal fun sha256Hex(data: ByteArray): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { "%02x".format(it) }
