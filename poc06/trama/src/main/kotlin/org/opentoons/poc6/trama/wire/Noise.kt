package org.opentoons.poc6.trama.wire

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * E1b — implementação do Noise Protocol Framework restrita ao que a PoC precisa:
 * padrão XX, DH25519, ChaCha20-Poly1305 e SHA-256 (protocolo
 * `Noise_XX_25519_ChaChaPoly_SHA256`), seguindo a revisão 34 da especificação.
 *
 * Cripto artesanal APENAS na orquestração (estados, HKDF, nonces): as primitivas são
 * do BouncyCastle. Validada contra os vetores de teste oficiais (NoiseVectorTest) —
 * requisito bloqueante da spec para uso no E2/E4.
 */
object NoiseProtocol {
    const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    const val MAX_MESSAGE = 65535
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32
}

data class NoiseKeyPair(val private: X25519PrivateKeyParameters, val public: X25519PublicKeyParameters) {
    companion object {
        fun generate(random: SecureRandom = SecureRandom()): NoiseKeyPair {
            val gen = X25519KeyPairGenerator()
            gen.init(X25519KeyGenerationParameters(random))
            val kp = gen.generateKeyPair()
            return NoiseKeyPair(
                kp.private as X25519PrivateKeyParameters,
                kp.public as X25519PublicKeyParameters,
            )
        }

        fun fromPrivate(bytes: ByteArray): NoiseKeyPair {
            val priv = X25519PrivateKeyParameters(bytes, 0)
            return NoiseKeyPair(priv, priv.generatePublicKey())
        }
    }
}

internal fun dh(private: X25519PrivateKeyParameters, public: X25519PublicKeyParameters): ByteArray {
    val agreement = X25519Agreement()
    agreement.init(private)
    val out = ByteArray(agreement.agreementSize)
    agreement.calculateAgreement(public, out, 0)
    return out
}

internal fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
    val mac = HMac(SHA256Digest())
    mac.init(KeyParameter(key))
    mac.update(data, 0, data.size)
    return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
}

/** HKDF da spec do Noise (§4.3): 2 ou 3 saídas de 32 bytes. */
internal fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
    require(outputs in 2..3)
    val tempKey = hmacSha256(chainingKey, ikm)
    val out1 = hmacSha256(tempKey, byteArrayOf(0x01))
    val out2 = hmacSha256(tempKey, out1 + byteArrayOf(0x02))
    if (outputs == 2) return listOf(out1, out2)
    val out3 = hmacSha256(tempKey, out2 + byteArrayOf(0x03))
    return listOf(out1, out2, out3)
}

class NoiseDecryptException(message: String) : SecurityException(message)

/**
 * CipherState (§5.1): chave + nonce de 64 bits. O nonce entra no ChaCha20-Poly1305
 * como IV de 12 bytes = 4 bytes zero || nonce little-endian.
 */
class CipherState {
    private var key: ByteArray? = null
    private var nonce: Long = 0

    fun initializeKey(k: ByteArray?) {
        key = k?.copyOf()
        nonce = 0
    }

    fun hasKey(): Boolean = key != null

    private fun iv(): ByteArray {
        val iv = ByteArray(12)
        var n = nonce
        for (i in 0 until 8) {
            iv[4 + i] = (n and 0xff).toByte()
            n = n ushr 8
        }
        return iv
    }

    private fun aead(encrypt: Boolean, ad: ByteArray, input: ByteArray): ByteArray {
        val k = key ?: return input.copyOf() // sem chave: passthrough (§5.2)
        check(nonce != -1L) { "nonce esgotado (2^64-1): a conexão deve ser reaberta" }
        val cipher = ChaCha20Poly1305()
        cipher.init(encrypt, ParametersWithIV(KeyParameter(k), iv()))
        cipher.processAADBytes(ad, 0, ad.size)
        val out = ByteArray(cipher.getOutputSize(input.size))
        val len = cipher.processBytes(input, 0, input.size, out, 0)
        try {
            cipher.doFinal(out, len)
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            throw NoiseDecryptException("AEAD falhou: ${e.message}")
        }
        nonce++
        return out
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray = aead(true, ad, plaintext)
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray = aead(false, ad, ciphertext)
}

/** SymmetricState (§5.2): chaining key + hash de transcript + CipherState. */
class SymmetricState(protocolName: String) {
    val cipher = CipherState()
    internal var chainingKey: ByteArray
    internal var hash: ByteArray

    init {
        val name = protocolName.toByteArray()
        hash = if (name.size <= 32) name.copyOf(32) else MessageDigest.getInstance("SHA-256").digest(name)
        chainingKey = hash.copyOf()
        cipher.initializeKey(null)
    }

    fun mixHash(data: ByteArray) {
        hash = MessageDigest.getInstance("SHA-256").digest(hash + data)
    }

    fun mixKey(ikm: ByteArray) {
        val (ck, tempK) = hkdf(chainingKey, ikm, 2)
        chainingKey = ck
        cipher.initializeKey(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encryptWithAd(hash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decryptWithAd(hash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf(chainingKey, ByteArray(0), 2)
        val c1 = CipherState().apply { initializeKey(k1) }
        val c2 = CipherState().apply { initializeKey(k2) }
        return c1 to c2
    }
}

/**
 * HandshakeState (§5.3) restrito ao padrão XX:
 * ```
 * XX:
 *   -> e
 *   <- e, ee, s, es
 *   -> s, se
 * ```
 */
class HandshakeState(
    val initiator: Boolean,
    prologue: ByteArray,
    private val localStatic: NoiseKeyPair,
    /** Injetável só para os vetores de teste; produção usa efêmera aleatória. */
    private var localEphemeral: NoiseKeyPair? = null,
) {
    private enum class Token { E, EE, S, ES, SE }

    private val pattern: List<List<Token>> = listOf(
        listOf(Token.E),
        listOf(Token.E, Token.EE, Token.S, Token.ES),
        listOf(Token.S, Token.SE),
    )

    private val symmetric = SymmetricState(NoiseProtocol.PROTOCOL_NAME)
    private var remoteStatic: X25519PublicKeyParameters? = null
    private var remoteEphemeral: X25519PublicKeyParameters? = null
    private var messageIndex = 0

    init {
        symmetric.mixHash(prologue)
        // XX não tem pre-messages
    }

    val complete: Boolean get() = messageIndex >= pattern.size
    val remoteStaticKey: X25519PublicKeyParameters
        get() = remoteStatic ?: error("chave estática remota ainda não recebida")
    val handshakeHash: ByteArray get() = symmetric.hash.copyOf()

    private fun myTurnToWrite(): Boolean = (messageIndex % 2 == 0) == initiator

    fun writeMessage(payload: ByteArray): ByteArray {
        check(!complete) { "handshake já concluído" }
        check(myTurnToWrite()) { "não é a vez deste lado escrever" }
        var message = ByteArray(0)
        for (token in pattern[messageIndex]) {
            when (token) {
                Token.E -> {
                    val e = localEphemeral ?: NoiseKeyPair.generate().also { localEphemeral = it }
                    message += e.public.encoded
                    symmetric.mixHash(e.public.encoded)
                }
                Token.S -> message += symmetric.encryptAndHash(localStatic.public.encoded)
                Token.EE -> symmetric.mixKey(dh(localEphemeral!!.private, remoteEphemeral!!))
                Token.ES -> symmetric.mixKey(
                    if (initiator) dh(localEphemeral!!.private, remoteStatic!!)
                    else dh(localStatic.private, remoteEphemeral!!),
                )
                Token.SE -> symmetric.mixKey(
                    if (initiator) dh(localStatic.private, remoteEphemeral!!)
                    else dh(localEphemeral!!.private, remoteStatic!!),
                )
            }
        }
        message += symmetric.encryptAndHash(payload)
        require(message.size <= NoiseProtocol.MAX_MESSAGE) { "mensagem Noise acima de 65535 B" }
        messageIndex++
        return message
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!complete) { "handshake já concluído" }
        check(!myTurnToWrite()) { "não é a vez deste lado ler" }
        require(message.size <= NoiseProtocol.MAX_MESSAGE) { "mensagem Noise acima de 65535 B" }
        var offset = 0
        for (token in pattern[messageIndex]) {
            when (token) {
                Token.E -> {
                    remoteEphemeral = X25519PublicKeyParameters(message, offset)
                    symmetric.mixHash(message.copyOfRange(offset, offset + 32))
                    offset += 32
                }
                Token.S -> {
                    val len = if (symmetric.cipher.hasKey()) 32 + NoiseProtocol.TAG_SIZE else 32
                    val encrypted = message.copyOfRange(offset, offset + len)
                    remoteStatic = X25519PublicKeyParameters(symmetric.decryptAndHash(encrypted), 0)
                    offset += len
                }
                Token.EE -> symmetric.mixKey(dh(localEphemeral!!.private, remoteEphemeral!!))
                Token.ES -> symmetric.mixKey(
                    if (initiator) dh(localEphemeral!!.private, remoteStatic!!)
                    else dh(localStatic.private, remoteEphemeral!!),
                )
                Token.SE -> symmetric.mixKey(
                    if (initiator) dh(localStatic.private, remoteEphemeral!!)
                    else dh(localEphemeral!!.private, remoteStatic!!),
                )
            }
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        messageIndex++
        return payload
    }

    /**
     * Estados de transporte pós-handshake (§5.3): o par (envio, recepção) já orientado
     * pelo papel — o initiator envia com a primeira chave do split.
     */
    fun transportPair(): Pair<CipherState, CipherState> {
        check(complete) { "handshake incompleto" }
        val (c1, c2) = symmetric.split()
        return if (initiator) c1 to c2 else c2 to c1
    }
}
