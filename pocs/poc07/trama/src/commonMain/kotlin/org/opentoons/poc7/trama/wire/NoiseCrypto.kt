package org.opentoons.poc7.trama.wire

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH

/**
 * poc-07 — primitivas do Noise XX sobre cryptography-kotlin (o caminho do spike 2.2): X25519,
 * ChaCha20-Poly1305 (nonce explícito), HMAC-SHA256, SHA-256. Substituem o BouncyCastle do
 * poc-06 SEM mudar o protocolo: a orquestração (HandshakeState/HKDF/nonces) é a mesma, então
 * o fio é idêntico e um nó JVM fala com um nó Native.
 */
@OptIn(DelicateCryptographyApi::class)
internal object NoiseCrypto {
    private val provider get() = CryptographyProvider.Default
    private val xdh get() = provider.get(XDH)
    private val hmac get() = provider.get(HMAC)
    private val chacha get() = provider.get(ChaCha20Poly1305)
    private val sha get() = provider.get(SHA256).hasher()

    fun sha256(data: ByteArray): ByteArray = sha.hashBlocking(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        hmac.keyDecoder(SHA256).decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator().generateSignatureBlocking(data)

    fun chachaEncrypt(key: ByteArray, iv: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray =
        chacha.keyDecoder().decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
            .cipher().encryptWithIvBlocking(iv, plaintext, ad)

    fun chachaDecrypt(key: ByteArray, iv: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray =
        chacha.keyDecoder().decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
            .cipher().decryptWithIvBlocking(iv, ciphertext, ad)

    fun publicFromRaw(raw: ByteArray): XDH.PublicKey =
        xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, raw)

    fun rawPublic(key: XDH.PublicKey): ByteArray =
        key.encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW)

    /** DH cru (X25519): 32 bytes de segredo compartilhado, como o `X25519Agreement` do BC. */
    fun dh(priv: XDH.PrivateKey, peerRaw: ByteArray): ByteArray =
        priv.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(publicFromRaw(peerRaw))
}

/** Par de chaves estático/efêmero do Noise: chave X25519 + pubkey crua (32 B) para o fio. */
internal class NoiseKeyPair(val privateKey: XDH.PrivateKey, val publicRaw: ByteArray) {
    companion object {
        fun generate(): NoiseKeyPair {
            val kp = CryptographyProvider.Default.get(XDH).keyPairGenerator(XDH.Curve.X25519).generateKeyBlocking()
            return NoiseKeyPair(kp.privateKey, NoiseCrypto.rawPublic(kp.publicKey))
        }
    }
}
