package org.opentoons.poc7.api

import dev.whyoleg.cryptography.algorithms.EdDSA

/**
 * poc-07 — identidade Ed25519 NEUTRA portada para `commonMain`. Mesma semântica do poc-06
 * (pubkey crua = id do nó; semente determinística = `sha256(seed)`), agora sobre
 * cryptography-kotlin. A superfície de verificação é por BYTES (não expõe tipos da lib),
 * o que mantém `ChapterVerifier`/Noise indiferentes ao provider.
 */
class NodeKeys private constructor(
    private val privateKey: EdDSA.PrivateKey,
    /** Pubkey crua de 32 B — a identidade do nó. */
    val publicKey: ByteArray,
) {
    val id: ByteArray get() = publicKey
    val idHex: String get() = publicKey.encodeHex()

    /** Semente crua de 32 B da chave privada (para o adapter inicializar o motor dele). */
    val privateSeed: ByteArray get() = Crypto.rawPrivate(privateKey)

    fun sign(data: ByteArray): ByteArray = Crypto.sign(privateKey, data)

    companion object {
        fun generate(): NodeKeys {
            val kp = Crypto.ed25519Generate()
            return NodeKeys(kp.privateKey, Crypto.rawPublic(kp.publicKey))
        }

        /** Identidade determinística por semente (técnica do poc-01 E5): priv = sha256(seed). */
        fun fromSeed(seed: String): NodeKeys {
            val priv = Crypto.ed25519FromSeed(Crypto.sha256(seed.encodeToByteArray()))
            return NodeKeys(priv, Crypto.ed25519PublicRawOf(priv))
        }

        /** Verificação neutra por bytes de pubkey (32 B). */
        fun verify(publicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean =
            Crypto.verify(Crypto.ed25519PublicFromRaw(publicKeyBytes), data, signature)

        fun verifyHex(publicKeyHex: String, data: ByteArray, signature: ByteArray): Boolean =
            verify(publicKeyHex.decodeHex(), data, signature)
    }
}
