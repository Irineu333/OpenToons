package org.opentoons.poc7.api

import kotlin.jvm.JvmInline

/**
 * poc-07 — tipos 100% NEUTROS do seam, portados do `:pocs:poc06:api` para `commonMain` SEM
 * mudança de contrato (design D2: reusar o seam, não redesenhar). `@JvmInline` é ignorado no
 * Native; `sha256Hex` agora vem da [Crypto] (cryptography-kotlin), não de `MessageDigest`.
 */

@JvmInline
value class ObraId(val value: String) {
    override fun toString(): String = value
}

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

data class Provider(val id: String, val addresses: List<String>)

data class BootstrapAddr(val host: String, val port: Int, val publicKeyHex: String)

data class ListenSpec(
    val port: Int,
    val publicHost: String? = null,
    val publicPort: Int? = null,
)

data class AnnounceTuning(
    val ttlMillis: Long = 5 * 60_000,
    val republishMillis: Long = 15_000,
)

enum class Capability {
    HOLE_PUNCH,
    ANONYMOUS_DIAL,
}

data class AnonymityConfig(
    val enabled: Boolean = false,
    val socksHost: String? = null,
    val socksPort: Int? = null,
) {
    init {
        require(!enabled || (socksHost != null && socksPort != null)) {
            "modo anônimo habilitado exige socksHost + socksPort"
        }
    }

    companion object {
        val DISABLED = AnonymityConfig(enabled = false)
        fun tor(socksHost: String = "127.0.0.1", socksPort: Int = 9050) =
            AnonymityConfig(enabled = true, socksHost = socksHost, socksPort = socksPort)
    }
}
