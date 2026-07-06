package org.opentoons.poc7.trama.wire

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opentoons.poc7.api.NodeKeys

/** Helpers BE de 4 bytes locais ao wire (o `:api` mantém os seus internos). */
private fun Int.be4(): ByteArray = byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), toByte())
private fun ByteArray.be4At(off: Int): Int =
    ((this[off].toInt() and 0xff) shl 24) or ((this[off + 1].toInt() and 0xff) shl 16) or
        ((this[off + 2].toInt() and 0xff) shl 8) or (this[off + 3].toInt() and 0xff)

/**
 * poc-07 — canal seguro Noise XX portado para `commonMain`/suspenso. A identidade Ed25519 é
 * ligada à chave estática Noise (esquema libp2p-noise): payload =
 * `identityPub(32) || sig(identityPriv, contexto || staticNoisePub)`. Quem valida rejeita o
 * par ANTES de qualquer dado de aplicação. Identidades como bytes crus (não vaza tipos de lib).
 */
object NoiseChannel {

    private const val BINDING_CONTEXT = "opentoons-noise-static:"

    class ImpostorException(message: String) : RuntimeException(message)

    private fun identityPayload(identity: NodeKeys, noiseStaticPub: ByteArray): ByteArray =
        identity.id + identity.sign(BINDING_CONTEXT.encodeToByteArray() + noiseStaticPub)

    private fun verifyIdentityPayload(
        payload: ByteArray,
        peerNoiseStatic: ByteArray,
        expectedPeer: ByteArray?,
    ): ByteArray {
        require(payload.size == 32 + 64) { "payload de identidade malformado (${payload.size} B)" }
        val identityPub = payload.copyOfRange(0, 32)
        val signature = payload.copyOfRange(32, 96)
        val signed = BINDING_CONTEXT.encodeToByteArray() + peerNoiseStatic
        if (!NodeKeys.verify(identityPub, signed, signature)) {
            throw ImpostorException("assinatura da chave estática Noise inválida")
        }
        if (expectedPeer != null && !identityPub.contentEquals(expectedPeer)) {
            throw ImpostorException("impostor: canal válido mas identidade não é a esperada")
        }
        return identityPub
    }

    /** Handshake XX como initiator (cliente). [expectedServer] = pubkey Ed25519 esperada. */
    suspend fun clientHandshake(
        conn: FrameConnection,
        identity: NodeKeys,
        expectedServer: ByteArray,
    ): SecureNoiseConnection {
        val noiseStatic = NoiseKeyPair.generate()
        val hs = HandshakeState(initiator = true, prologue = ByteArray(0), localStatic = noiseStatic)

        conn.send(hs.writeMessage(ByteArray(0)))                                   // -> e
        val serverPayload = hs.readMessage(conn.receive() ?: error("EOF no handshake")) // <- e, ee, s, es
        val serverId = verifyIdentityPayload(serverPayload, hs.remoteStaticKey, expectedServer)
        conn.send(hs.writeMessage(identityPayload(identity, noiseStatic.publicRaw))) // -> s, se

        val (send, recv) = hs.transportPair()
        return SecureNoiseConnection(conn, send, recv, serverId)
    }

    /** Handshake XX como responder (nó pleno); autoriza qualquer identidade provada. */
    suspend fun serverHandshake(conn: FrameConnection, identity: NodeKeys): SecureNoiseConnection {
        val noiseStatic = NoiseKeyPair.generate()
        val hs = HandshakeState(initiator = false, prologue = ByteArray(0), localStatic = noiseStatic)

        hs.readMessage(conn.receive() ?: error("EOF no handshake"))                  // -> e
        conn.send(hs.writeMessage(identityPayload(identity, noiseStatic.publicRaw)))  // <- e, ee, s, es
        val clientPayload = hs.readMessage(conn.receive() ?: error("EOF no handshake")) // -> s, se
        val clientId = verifyIdentityPayload(clientPayload, hs.remoteStaticKey, null)

        val (send, recv) = hs.transportPair()
        return SecureNoiseConnection(conn, send, recv, clientId)
    }
}

/**
 * Conexão cifrada pós-handshake. Frames de aplicação maiores que uma mensagem Noise são
 * fatiados em até [CHUNK] e remontados na leitura — o prefixo BE de 4 bytes com o tamanho
 * total vai dentro da primeira mensagem cifrada. IO suspensa.
 */
class SecureNoiseConnection(
    private val inner: FrameConnection,
    private val sendState: CipherState,
    private val recvState: CipherState,
    /** Identidade Ed25519 (32 B) provada pelo par. */
    val peerIdentity: ByteArray,
) : FrameConnection {

    companion object {
        const val CHUNK = NoiseProtocol.MAX_MESSAGE - NoiseProtocol.TAG_SIZE
    }

    override val remoteDescription: String get() = "noise:${inner.remoteDescription}"

    // O CipherState (chave+nonce) e o fatiamento em chunks NÃO são concorrentes: dois envios
    // simultâneos (ex.: o servidor respondendo 3 GET_BLOCK em paralelo na mesma conexão)
    // intercalariam chunks e racejariam o nonce. Serializa cada sentido com um Mutex.
    private val sendMutex = Mutex()
    private val recvMutex = Mutex()

    override suspend fun send(payload: ByteArray) = sendMutex.withLock {
        val framed = payload.size.be4() + payload
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + CHUNK, framed.size)
            inner.send(sendState.encryptWithAd(ByteArray(0), framed.copyOfRange(offset, end)))
            offset = end
        }
    }

    override suspend fun receive(): ByteArray? = recvMutex.withLock {
        val first = inner.receive() ?: return@withLock null
        val head = recvState.decryptWithAd(ByteArray(0), first)
        require(head.size >= 4) { "frame cifrado sem cabeçalho" }
        val total = head.be4At(0)
        require(total in 0..Frames.MAX_FRAME) { "frame de $total B fora do limite" }
        val out = ByteArray(total)
        var pos = minOf(head.size - 4, total)
        head.copyInto(out, 0, 4, 4 + pos)
        while (pos < total) {
            val chunk = inner.receive() ?: error("EOF no meio de um frame cifrado")
            val dec = recvState.decryptWithAd(ByteArray(0), chunk)
            dec.copyInto(out, pos); pos += dec.size
        }
        require(pos == total) { "remontagem de frame divergente" }
        return@withLock out
    }

    override fun close() = inner.close()
}
