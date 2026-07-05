package org.opentoons.poc6.trama.wire

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc6.api.NodeKeys
import org.opentoons.poc6.trama.wire.FrameConnection
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * E1b — canal seguro Noise XX sobre o transporte de frames, com a identidade Ed25519
 * ligada à chave estática Noise (esquema do libp2p-noise, design D2): o payload do
 * handshake carrega `identityPub(32) || sig(identityPriv, contexto || staticNoisePub)`.
 *
 * Quem valida o payload rejeita o par ANTES de qualquer dado de aplicação — mesma
 * garantia do TrustManager no E1a.
 */
object NoiseChannel {

    private const val BINDING_CONTEXT = "opentoons-noise-static:"

    class ImpostorException(message: String) : SecurityException(message)

    private fun identityPayload(identity: NodeKeys, noiseStatic: NoiseKeyPair): ByteArray =
        identity.id + identity.sign(BINDING_CONTEXT.toByteArray() + noiseStatic.public.encoded)

    private fun verifyIdentityPayload(
        payload: ByteArray,
        peerNoiseStatic: ByteArray,
        expectedPeer: Ed25519PublicKeyParameters?,
    ): Ed25519PublicKeyParameters {
        require(payload.size == 32 + 64) { "payload de identidade malformado (${payload.size} B)" }
        val identityPub = Ed25519PublicKeyParameters(payload, 0)
        val signature = payload.copyOfRange(32, 96)
        val signed = BINDING_CONTEXT.toByteArray() + peerNoiseStatic
        if (!NodeKeys.verify(identityPub, signed, signature)) {
            throw ImpostorException("assinatura da chave estática Noise inválida")
        }
        if (expectedPeer != null && !identityPub.encoded.contentEquals(expectedPeer.encoded)) {
            throw ImpostorException("impostor: canal válido mas identidade não é a esperada")
        }
        return identityPub
    }

    /**
     * Executa o handshake XX como initiator (cliente). [expectedServer] é obrigatório:
     * o cliente sempre sabe quem está discando (mesmo modelo do E1a).
     */
    fun clientHandshake(
        conn: FrameConnection,
        identity: NodeKeys,
        expectedServer: Ed25519PublicKeyParameters,
    ): SecureNoiseConnection {
        val noiseStatic = NoiseKeyPair.generate()
        val hs = HandshakeState(initiator = true, prologue = ByteArray(0), localStatic = noiseStatic)

        conn.send(hs.writeMessage(ByteArray(0)))                       // -> e
        val serverPayload = hs.readMessage(conn.receive() ?: error("EOF no handshake")) // <- e, ee, s, es
        val serverId = verifyIdentityPayload(serverPayload, hs.remoteStaticKey.encoded, expectedServer)
        conn.send(hs.writeMessage(identityPayload(identity, noiseStatic))) // -> s, se

        val (send, recv) = hs.transportPair()
        return SecureNoiseConnection(conn, send, recv, serverId)
    }

    /** Executa o handshake XX como responder (nó pleno); autoriza qualquer identidade provada. */
    fun serverHandshake(
        conn: FrameConnection,
        identity: NodeKeys,
    ): SecureNoiseConnection {
        val noiseStatic = NoiseKeyPair.generate()
        val hs = HandshakeState(initiator = false, prologue = ByteArray(0), localStatic = noiseStatic)

        hs.readMessage(conn.receive() ?: error("EOF no handshake"))    // -> e
        conn.send(hs.writeMessage(identityPayload(identity, noiseStatic))) // <- e, ee, s, es
        val clientPayload = hs.readMessage(conn.receive() ?: error("EOF no handshake")) // -> s, se
        val clientId = verifyIdentityPayload(clientPayload, hs.remoteStaticKey.encoded, null)

        val (send, recv) = hs.transportPair()
        return SecureNoiseConnection(conn, send, recv, clientId)
    }
}

/**
 * Conexão cifrada pós-handshake. Frames de aplicação maiores que uma mensagem Noise
 * (65535 B com tag) são fatiados em mensagens de até [CHUNK] e remontados na leitura —
 * o prefixo de 4 bytes com o tamanho total vai dentro da primeira mensagem cifrada.
 */
class SecureNoiseConnection(
    private val inner: FrameConnection,
    private val sendState: CipherState,
    private val recvState: CipherState,
    val peerIdentity: Ed25519PublicKeyParameters,
) : FrameConnection {

    companion object {
        const val CHUNK = NoiseProtocol.MAX_MESSAGE - NoiseProtocol.TAG_SIZE
    }

    override val remoteDescription: String get() = "noise:${inner.remoteDescription}"

    override fun send(payload: ByteArray) = synchronized(sendState) {
        val framed = ByteBuffer.allocate(4).putInt(payload.size).array() + payload
        var offset = 0
        while (offset < framed.size) {
            val end = minOf(offset + CHUNK, framed.size)
            inner.send(sendState.encryptWithAd(ByteArray(0), framed.copyOfRange(offset, end)))
            offset = end
        }
    }

    override fun receive(): ByteArray? = synchronized(recvState) {
        val first = inner.receive() ?: return null
        val head = recvState.decryptWithAd(ByteArray(0), first)
        require(head.size >= 4) { "frame cifrado sem cabeçalho" }
        val total = ByteBuffer.wrap(head, 0, 4).int
        require(total in 0..Frames.MAX_FRAME) { "frame de $total B fora do limite" }
        val out = ByteArrayOutputStream(total + 4)
        out.write(head, 4, head.size - 4)
        while (out.size() < total) {
            val chunk = inner.receive() ?: throw IllegalStateException("EOF no meio de um frame cifrado")
            out.write(recvState.decryptWithAd(ByteArray(0), chunk))
        }
        val bytes = out.toByteArray()
        require(bytes.size == total) { "remontagem de frame divergente" }
        return bytes
    }

    override fun close() = inner.close()
}
