package org.opentoons.poc6.api

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PoC poc-04 — identidade Ed25519 NEUTRA (reuso do NodeIdentity do poc-02, D8). A mesma
 * chave é a identidade nos dois backends: a Trama usa a pubkey crua como id de nó; o
 * adapter libp2p deriva a identidade interna dele da MESMA semente ([privateSeed]) — por
 * isso a semente é exposta: ela cruza para dentro do adapter, nunca para a superfície.
 */
class NodeKeys(
    private val privateKey: Ed25519PrivateKeyParameters,
    val publicKey: Ed25519PublicKeyParameters,
) {
    val id: ByteArray get() = publicKey.encoded
    val idHex: String get() = id.joinToString("") { "%02x".format(it) }

    /** Semente crua de 32 B da chave privada (para o adapter inicializar o motor dele). */
    val privateSeed: ByteArray get() = privateKey.encoded

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): NodeKeys {
            val priv = Ed25519PrivateKeyParameters(random)
            return NodeKeys(priv, priv.generatePublicKey())
        }

        /** Identidade determinística por semente (técnica do poc-01 E5): priv = sha256(seed). */
        fun fromSeed(seed: String): NodeKeys {
            val priv = Ed25519PrivateKeyParameters(
                MessageDigest.getInstance("SHA-256").digest(seed.toByteArray()), 0,
            )
            return NodeKeys(priv, priv.generatePublicKey())
        }

        fun verify(pubKey: Ed25519PublicKeyParameters, data: ByteArray, signature: ByteArray): Boolean {
            val verifier = Ed25519Signer()
            verifier.init(false, pubKey)
            verifier.update(data, 0, data.size)
            return verifier.verifySignature(signature)
        }

        fun publicKeyFromHex(hex: String): Ed25519PublicKeyParameters =
            Ed25519PublicKeyParameters(hex.decodeHex(), 0)
    }
}

fun String.decodeHex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()

fun ByteArray.encodeHex(): String = joinToString("") { "%02x".format(it) }
