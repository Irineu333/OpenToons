package org.opentoons.poc2.noise

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.opentoons.poc2.core.hexToBytes
import org.opentoons.poc2.core.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3.2 — validação BLOQUEANTE contra os vetores de teste oficiais do Noise
 * (`Noise_XX_25519_ChaChaPoly_SHA256`), extraídos das suítes do cacophony e do snow
 * (implementações independentes). Falha aqui impede o uso da variante no E2/E4.
 *
 * Convenção dos vetores: mensagens alternam initiator/responder começando pelo
 * initiator, inclusive as mensagens de transporte após o handshake.
 */
class NoiseVectorTest {

    @Serializable
    data class VectorMessage(val payload: String, val ciphertext: String)

    @Serializable
    data class Vector(
        val protocol_name: String,
        val init_prologue: String = "",
        val init_static: String? = null,
        val init_ephemeral: String,
        val resp_prologue: String = "",
        val resp_static: String? = null,
        val resp_ephemeral: String? = null,
        val handshake_hash: String? = null,
        val messages: List<VectorMessage>,
    )

    @Serializable
    data class VectorFile(val vectors: List<Vector>)

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadVectors(): List<Vector> {
        val resource = checkNotNull(
            javaClass.getResourceAsStream("/noise-vectors-xx-25519-chachapoly-sha256.json"),
        ) { "arquivo de vetores não encontrado" }
        return json.decodeFromString<VectorFile>(resource.reader().readText()).vectors
    }

    @Test
    fun `todos os vetores oficiais XX 25519 ChaChaPoly SHA256 passam`() {
        val vectors = loadVectors()
        assertTrue(vectors.size >= 2, "esperados >= 2 vetores oficiais, achados ${vectors.size}")

        vectors.forEachIndexed { vi, v ->
            assertEquals(NoiseProtocol.PROTOCOL_NAME, v.protocol_name)

            val initiator = HandshakeState(
                initiator = true,
                prologue = v.init_prologue.hexToBytes(),
                localStatic = NoiseKeyPair.fromPrivate(v.init_static!!.hexToBytes()),
                localEphemeral = NoiseKeyPair.fromPrivate(v.init_ephemeral.hexToBytes()),
            )
            val responder = HandshakeState(
                initiator = false,
                prologue = v.resp_prologue.hexToBytes(),
                localStatic = NoiseKeyPair.fromPrivate(v.resp_static!!.hexToBytes()),
                localEphemeral = NoiseKeyPair.fromPrivate(v.resp_ephemeral!!.hexToBytes()),
            )

            // fase de handshake: 3 mensagens do XX
            v.messages.take(3).forEachIndexed { i, msg ->
                val (sender, receiver) = if (i % 2 == 0) initiator to responder else responder to initiator
                val wire = sender.writeMessage(msg.payload.hexToBytes())
                assertEquals(msg.ciphertext, wire.toHex(), "vetor $vi, mensagem $i (escrita)")
                val payload = receiver.readMessage(wire)
                assertEquals(msg.payload, payload.toHex(), "vetor $vi, mensagem $i (leitura)")
            }

            v.handshake_hash?.let {
                assertEquals(it, initiator.handshakeHash.toHex(), "vetor $vi: handshake_hash")
                assertEquals(it, responder.handshakeHash.toHex(), "vetor $vi: handshake_hash (responder)")
            }

            // fase de transporte: mensagens seguintes continuam alternando
            val (initSend, initRecv) = initiator.transportPair()
            val (respSend, respRecv) = responder.transportPair()
            v.messages.drop(3).forEachIndexed { j, msg ->
                val i = j + 3
                val (sendCs, recvCs) = if (i % 2 == 0) initSend to respRecv else respSend to initRecv
                val wire = sendCs.encryptWithAd(ByteArray(0), msg.payload.hexToBytes())
                assertEquals(msg.ciphertext, wire.toHex(), "vetor $vi, transporte $i (cifra)")
                val plain = recvCs.decryptWithAd(ByteArray(0), msg.ciphertext.hexToBytes())
                assertEquals(msg.payload, plain.toHex(), "vetor $vi, transporte $i (decifra)")
            }
        }
    }
}
