package org.opentoons.poc7.trama.wire

/**
 * poc-07 — Noise XX (`Noise_XX_25519_ChaChaPoly_SHA256`) portado para `commonMain`. A
 * orquestração (estados, HKDF, nonces) é a MESMA do poc-06 — validada contra os vetores
 * oficiais lá; só as primitivas passaram para [NoiseCrypto] (cryptography-kotlin). O fio é
 * idêntico, então um handshake entre um lado JVM e um lado Kotlin/Native fecha.
 */
object NoiseProtocol {
    const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    const val MAX_MESSAGE = 65535
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32
}

class NoiseDecryptException(message: String) : RuntimeException(message)

/** HKDF da spec do Noise (§4.3): 2 ou 3 saídas de 32 bytes. */
internal fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
    require(outputs in 2..3)
    val tempKey = NoiseCrypto.hmacSha256(chainingKey, ikm)
    val out1 = NoiseCrypto.hmacSha256(tempKey, byteArrayOf(0x01))
    val out2 = NoiseCrypto.hmacSha256(tempKey, out1 + byteArrayOf(0x02))
    if (outputs == 2) return listOf(out1, out2)
    val out3 = NoiseCrypto.hmacSha256(tempKey, out2 + byteArrayOf(0x03))
    return listOf(out1, out2, out3)
}

/** CipherState (§5.1): chave + nonce de 64 bits; IV de 12 B = 4 zero || nonce LE. */
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
        val out = try {
            if (encrypt) NoiseCrypto.chachaEncrypt(k, iv(), ad, input)
            else NoiseCrypto.chachaDecrypt(k, iv(), ad, input)
        } catch (e: Exception) {
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
        val name = protocolName.encodeToByteArray()
        hash = if (name.size <= 32) name.copyOf(32) else NoiseCrypto.sha256(name)
        chainingKey = hash.copyOf()
        cipher.initializeKey(null)
    }

    fun mixHash(data: ByteArray) {
        hash = NoiseCrypto.sha256(hash + data)
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
 * -> e ; <- e, ee, s, es ; -> s, se
 * ```
 * Chaves remotas guardadas como bytes crus (32 B) — DH via [NoiseCrypto.dh].
 */
internal class HandshakeState(
    val initiator: Boolean,
    prologue: ByteArray,
    private val localStatic: NoiseKeyPair,
    private var localEphemeral: NoiseKeyPair? = null,
) {
    private enum class Token { E, EE, S, ES, SE }

    private val pattern: List<List<Token>> = listOf(
        listOf(Token.E),
        listOf(Token.E, Token.EE, Token.S, Token.ES),
        listOf(Token.S, Token.SE),
    )

    private val symmetric = SymmetricState(NoiseProtocol.PROTOCOL_NAME)
    private var remoteStatic: ByteArray? = null
    private var remoteEphemeral: ByteArray? = null
    private var messageIndex = 0

    init {
        symmetric.mixHash(prologue)
    }

    val complete: Boolean get() = messageIndex >= pattern.size
    val remoteStaticKey: ByteArray get() = remoteStatic ?: error("chave estática remota ainda não recebida")
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
                    message += e.publicRaw
                    symmetric.mixHash(e.publicRaw)
                }
                Token.S -> message += symmetric.encryptAndHash(localStatic.publicRaw)
                Token.EE -> symmetric.mixKey(NoiseCrypto.dh(localEphemeral!!.privateKey, remoteEphemeral!!))
                Token.ES -> symmetric.mixKey(
                    if (initiator) NoiseCrypto.dh(localEphemeral!!.privateKey, remoteStatic!!)
                    else NoiseCrypto.dh(localStatic.privateKey, remoteEphemeral!!),
                )
                Token.SE -> symmetric.mixKey(
                    if (initiator) NoiseCrypto.dh(localStatic.privateKey, remoteEphemeral!!)
                    else NoiseCrypto.dh(localEphemeral!!.privateKey, remoteStatic!!),
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
                    remoteEphemeral = message.copyOfRange(offset, offset + 32)
                    symmetric.mixHash(remoteEphemeral!!)
                    offset += 32
                }
                Token.S -> {
                    val len = if (symmetric.cipher.hasKey()) 32 + NoiseProtocol.TAG_SIZE else 32
                    val encrypted = message.copyOfRange(offset, offset + len)
                    remoteStatic = symmetric.decryptAndHash(encrypted)
                    offset += len
                }
                Token.EE -> symmetric.mixKey(NoiseCrypto.dh(localEphemeral!!.privateKey, remoteEphemeral!!))
                Token.ES -> symmetric.mixKey(
                    if (initiator) NoiseCrypto.dh(localEphemeral!!.privateKey, remoteStatic!!)
                    else NoiseCrypto.dh(localStatic.privateKey, remoteEphemeral!!),
                )
                Token.SE -> symmetric.mixKey(
                    if (initiator) NoiseCrypto.dh(localStatic.privateKey, remoteEphemeral!!)
                    else NoiseCrypto.dh(localEphemeral!!.privateKey, remoteStatic!!),
                )
            }
        }
        val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
        messageIndex++
        return payload
    }

    fun transportPair(): Pair<CipherState, CipherState> {
        check(complete) { "handshake incompleto" }
        val (c1, c2) = symmetric.split()
        return if (initiator) c1 to c2 else c2 to c1
    }
}
