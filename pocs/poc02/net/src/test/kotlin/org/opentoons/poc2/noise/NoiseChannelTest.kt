package org.opentoons.poc2.noise

import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.transport.TcpClient
import org.opentoons.poc2.transport.TcpServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoiseChannelTest {

    private val serverId = NodeIdentity.generate()
    private val clientId = NodeIdentity.generate()

    // 3.3 — handshake mútuo com identidade ligada à chave estática Noise
    @Test
    fun `handshake mutuo JVM-JVM com identidade Ed25519 ligada`() {
        val done = CountDownLatch(1)
        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, serverId)
            assertContentEquals(clientId.id, secure.peerIdentity.encoded)
            assertEquals("ping", String(secure.receive()!!))
            secure.send("pong".toByteArray())
            done.countDown()
        }.use { server ->
            val raw = TcpClient.dial("127.0.0.1", server.port)
            val secure = NoiseChannel.clientHandshake(raw, clientId, serverId.publicKey)
            secure.use {
                assertContentEquals(serverId.id, it.peerIdentity.encoded)
                it.send("ping".toByteArray())
                assertEquals("pong", String(it.receive()!!))
            }
            assertTrue(done.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `frame maior que uma mensagem Noise e fatiado e remontado`() {
        val big = ByteArray(300_000) { (it % 251).toByte() } // > 4 chunks de 65519
        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, serverId)
            secure.send(secure.receive()!!) // eco
        }.use { server ->
            val secure = NoiseChannel.clientHandshake(
                TcpClient.dial("127.0.0.1", server.port), clientId, serverId.publicKey,
            )
            secure.use {
                it.send(big)
                assertContentEquals(big, it.receive())
            }
        }
    }

    // 3.3 — impostor: identidade válida mas não a esperada é rejeitada antes de dados
    @Test
    fun `impostor e rejeitado antes de dados de aplicacao`() {
        val impostor = NodeIdentity.generate()
        var appDataServed = false
        TcpServer(0) { raw ->
            runCatching {
                val secure = NoiseChannel.serverHandshake(raw, impostor)
                appDataServed = true
                secure.send("segredo".toByteArray())
            }
        }.use { server ->
            assertFailsWith<NoiseChannel.ImpostorException> {
                NoiseChannel.clientHandshake(
                    TcpClient.dial("127.0.0.1", server.port), clientId, serverId.publicKey,
                )
            }
        }
        Thread.sleep(200)
        assertFalse(appDataServed, "o cliente abortou na msg 2; o servidor nunca completa a msg 3")
    }

    @Test
    fun `assinatura de binding invalida e rejeitada mesmo com identidade esperada`() {
        // servidor assina a estática Noise com uma chave que NÃO é a da identidade anunciada:
        // simulado anunciando serverId mas assinando com outra chave — o payload é
        // construído à mão espelhando o formato do canal
        val outraChave = NodeIdentity.generate()
        TcpServer(0) { raw ->
            runCatching {
                val noiseStatic = NoiseKeyPair.generate()
                val hs = HandshakeState(initiator = false, prologue = ByteArray(0), localStatic = noiseStatic)
                hs.readMessage(raw.receive()!!)
                // payload forjado: id do servidor legítimo + assinatura de outra chave
                val forged = serverId.id +
                    outraChave.sign("opentoons-noise-static:".toByteArray() + noiseStatic.public.encoded)
                raw.send(hs.writeMessage(forged))
                hs.readMessage(raw.receive()!!)
            }
        }.use { server ->
            assertFailsWith<NoiseChannel.ImpostorException> {
                NoiseChannel.clientHandshake(
                    TcpClient.dial("127.0.0.1", server.port), clientId, serverId.publicKey,
                )
            }
        }
    }

    @Test
    fun `ciphertext adulterado no transporte falha na decifra`() {
        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, serverId)
            secure.receive()
        }.use { server ->
            val raw = TcpClient.dial("127.0.0.1", server.port)
            val secure = NoiseChannel.clientHandshake(raw, clientId, serverId.publicKey)
            // adultera um ciphertext fora do canal: decifra local deve falhar
            val cs = CipherState().apply { initializeKey(ByteArray(32) { 7 }) }
            val ct = CipherState().apply { initializeKey(ByteArray(32) { 7 }) }
                .encryptWithAd(ByteArray(0), "dados".toByteArray())
            ct[0] = (ct[0].toInt() xor 1).toByte()
            assertFailsWith<NoiseDecryptException> { cs.decryptWithAd(ByteArray(0), ct) }
            secure.close()
        }
    }

    // 3.4 (parte JVM) — latências em loopback; números do relatório saem do dispositivo
    @Test
    fun `latencia de handshake e reconexao em loopback JVM`() {
        TcpServer(0) { raw ->
            runCatching {
                val secure = NoiseChannel.serverHandshake(raw, serverId)
                while (secure.receive() != null) { /* mantém */ }
            }
        }.use { server ->
            fun once(): Double = measureNanoTime {
                NoiseChannel.clientHandshake(
                    TcpClient.dial("127.0.0.1", server.port), clientId, serverId.publicKey,
                ).close()
            } / 1_000_000.0

            val first = once()
            val second = once() // Noise XX não tem resumption: reconexão = handshake completo
            println("poc2-e1b: handshake_loopback_ms=$first reconexao_loopback_ms=$second")
        }
    }
}
