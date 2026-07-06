package org.opentoons.poc7.api

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.EdDSA
import dev.whyoleg.cryptography.algorithms.SHA256

/**
 * Crypto NEUTRA do seam (sha-256 + Ed25519), via cryptography-kotlin — o caminho escolhido
 * pelo spike 2.2 (JDK no host/Android, CryptoKit no iOS). Substitui `MessageDigest` e o
 * BouncyCastle Ed25519 do poc-06 SEM mudar formato: a mesma semente dá a mesma identidade,
 * a mesma assinatura verifica igual entre plataformas.
 */
@OptIn(DelicateCryptographyApi::class)
internal object Crypto {
    private val provider get() = CryptographyProvider.Default
    private val sha256 get() = provider.get(SHA256).hasher()
    private val eddsa get() = provider.get(EdDSA)

    fun sha256(data: ByteArray): ByteArray = sha256.hashBlocking(data)

    // ---- Ed25519 (RAW = semente/pubkey de 32 B, mesmo layout do BouncyCastle) ----

    fun ed25519FromSeed(seed32: ByteArray): EdDSA.PrivateKey =
        eddsa.privateKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PrivateKey.Format.RAW, seed32)

    fun ed25519Generate(): EdDSA.KeyPair =
        eddsa.keyPairGenerator(EdDSA.Curve.Ed25519).generateKeyBlocking()

    fun ed25519PublicFromRaw(pub32: ByteArray): EdDSA.PublicKey =
        eddsa.publicKeyDecoder(EdDSA.Curve.Ed25519)
            .decodeFromByteArrayBlocking(EdDSA.PublicKey.Format.RAW, pub32)

    /** Pubkey crua (32 B) derivada da privada — para a identidade determinística por semente. */
    fun ed25519PublicRawOf(priv: EdDSA.PrivateKey): ByteArray =
        rawPublic(priv.getPublicKeyBlocking())

    fun rawPublic(key: EdDSA.PublicKey): ByteArray =
        key.encodeToByteArrayBlocking(EdDSA.PublicKey.Format.RAW)

    fun rawPrivate(key: EdDSA.PrivateKey): ByteArray =
        key.encodeToByteArrayBlocking(EdDSA.PrivateKey.Format.RAW)

    fun sign(key: EdDSA.PrivateKey, data: ByteArray): ByteArray =
        key.signatureGenerator().generateSignatureBlocking(data)

    fun verify(key: EdDSA.PublicKey, data: ByteArray, signature: ByteArray): Boolean =
        key.signatureVerifier().tryVerifySignatureBlocking(data, signature)
}

internal fun sha256Hex(data: ByteArray): String = Crypto.sha256(data).encodeHex()
