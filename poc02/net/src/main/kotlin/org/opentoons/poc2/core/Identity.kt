package org.opentoons.poc2.core

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Identidade de nó da PoC poc-02: um par Ed25519 (mesmo mecanismo validado no E3 do
 * poc-01, design D8). O nodeId é a própria chave pública (32 bytes) — sem multihash,
 * sem peerId de libp2p; a rede é própria e o formato é nosso.
 */
class NodeIdentity(
    val privateKey: Ed25519PrivateKeyParameters,
    val publicKey: Ed25519PublicKeyParameters,
) {
    val id: ByteArray get() = publicKey.encoded
    val idHex: String get() = id.toHex()

    fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): NodeIdentity {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(random))
            val kp = gen.generateKeyPair()
            return NodeIdentity(
                kp.private as Ed25519PrivateKeyParameters,
                kp.public as Ed25519PublicKeyParameters,
            )
        }

        /** Identidade determinística por seed — mesma técnica do E5 do poc-01 (malha a priori). */
        fun fromSeed(seed: ByteArray): NodeIdentity {
            val priv = Ed25519PrivateKeyParameters(sha256(seed), 0)
            return NodeIdentity(priv, priv.generatePublicKey())
        }

        fun verify(pubKey: Ed25519PublicKeyParameters, data: ByteArray, signature: ByteArray): Boolean {
            val verifier = Ed25519Signer()
            verifier.init(false, pubKey)
            verifier.update(data, 0, data.size)
            return verifier.verifySignature(signature)
        }
    }
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex de tamanho ímpar" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
