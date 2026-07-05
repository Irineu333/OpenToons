package org.opentoons.poc2.tls

import org.opentoons.poc2.core.NodeIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TlsChannelTest {

    private val serverId = NodeIdentity.generate()
    private val clientId = NodeIdentity.generate()

    /**
     * 2.1 — questão aberta do design: certificado Ed25519 DIRETO funciona no JSSE?
     * Este teste responde para a JVM (JDK 21); a resposta Android sai no dispositivo (2.3).
     */
    @Test
    fun `Q1 JVM - handshake mutuo com certificado Ed25519 direto`() {
        val received = CountDownLatch(1)
        TlsChannel.Server(0, TlsIdentity.ed25519Cert(serverId)) { conn, peer ->
            assertContentEquals(clientId.id, peer.encoded)
            assertEquals("ping", String(conn.receive()!!))
            conn.send("pong".toByteArray())
            received.countDown()
        }.use { server ->
            val (conn, socket) = TlsChannel.dial(
                "127.0.0.1", server.port, TlsIdentity.ed25519Cert(clientId), serverId.publicKey,
            )
            conn.use {
                assertEquals("TLSv1.3", socket.session.protocol)
                it.send("ping".toByteArray())
                assertEquals("pong", String(it.receive()!!))
            }
            assertTrue(received.await(5, TimeUnit.SECONDS))
        }
    }

    // 2.2 — handshake mútuo JVM↔JVM com cert ECDSA + extensão de identidade (esquema libp2p-tls)
    @Test
    fun `handshake mutuo com cert ECDSA e extensao de identidade assinada`() {
        val received = CountDownLatch(1)
        TlsChannel.Server(0, TlsIdentity.boundCert(serverId)) { conn, peer ->
            // o servidor autentica o CLIENTE (mútuo): identidade extraída = identidade real
            assertContentEquals(clientId.id, peer.encoded)
            conn.send("hello".toByteArray())
            received.countDown()
        }.use { server ->
            val (conn, socket) = TlsChannel.dial(
                "127.0.0.1", server.port, TlsIdentity.boundCert(clientId), serverId.publicKey,
            )
            conn.use {
                assertEquals("TLSv1.3", socket.session.protocol)
                assertContentEquals(serverId.id, TlsChannel.peerIdentity(socket).encoded)
                assertEquals("hello", String(it.receive()!!))
            }
            assertTrue(received.await(5, TimeUnit.SECONDS))
        }
    }

    // 2.4 — impostor: canal tecnicamente válido, identidade errada → rejeitado no handshake
    @Test
    fun `impostor com canal valido e identidade errada e rejeitado antes de dados`() {
        val impostor = NodeIdentity.generate() // identidade real e válida — mas não a esperada
        var appDataServed = false
        TlsChannel.Server(0, TlsIdentity.boundCert(impostor)) { conn, _ ->
            appDataServed = true
            conn.send("segredo".toByteArray())
        }.use { server ->
            // o JSSE embrulha a SecurityException do TrustManager em SSLException genérica
            val failure = assertFailsWith<javax.net.ssl.SSLException> {
                // cliente espera serverId; o servidor prova ser impostor
                TlsChannel.dial(
                    "127.0.0.1", server.port, TlsIdentity.boundCert(clientId), serverId.publicKey,
                )
            }
            assertTrue(failure.message!!.contains("impostor"))
        }
        Thread.sleep(200)
        kotlin.test.assertFalse(appDataServed, "handler do servidor não pode ter recebido conexão autenticada")
    }

    @Test
    fun `extensao com assinatura de binding forjada e rejeitada`() {
        // certificado do impostor: extensão declara a identidade do servidor legítimo,
        // mas a assinatura de binding não pode ser produzida sem a chave privada dele —
        // um binding de OUTRA chave de canal é replay e falha na verificação
        val forged = TlsIdentity.boundCert(serverId) // legítimo…
        val attacker = TlsIdentity.boundCert(NodeIdentity.generate()) // …mas o atacante só tem o próprio
        // monta o ataque: apresenta o cert do atacante alegando a identidade do servidor.
        // Sem API para montar o híbrido via JSSE, validamos a guarda diretamente:
        val identity = TlsIdentity.extractIdentity(forged.certificate) // binding legítimo passa
        assertContentEquals(serverId.id, identity.encoded)
        assertFailsWith<SecurityException> {
            // extensão do cert legítimo transplantada para a chave de canal do atacante
            // = mesmo conteúdo, SPKI diferente → assinatura não bate
            TlsIdentityTestHooks.extractWithTransplantedExtension(forged.certificate, attacker.certificate)
        }
    }

    // 2.5 (parte JVM) — latências em loopback; os números do relatório saem do dispositivo
    @Test
    fun `latencia de handshake e reconexao em loopback JVM`() {
        TlsChannel.Server(0, TlsIdentity.boundCert(serverId)) { conn, _ ->
            while (conn.receive() != null) { /* mantém a conexão */ }
        }.use { server ->
            val dialer = TlsChannel.Dialer(TlsIdentity.boundCert(clientId), serverId.publicKey)

            val first = measureNanoTime {
                dialer.dial("127.0.0.1", server.port).first.close()
            }
            val second = measureNanoTime {
                val (conn, socket) = dialer.dial("127.0.0.1", server.port)
                println("poc2-e1a: reconexão retomada=${socket.session.isValid}")
                conn.close()
            }
            println("poc2-e1a: handshake_loopback_ms=${first / 1_000_000.0} reconexao_loopback_ms=${second / 1_000_000.0}")
            // sem asserção de limiar: loopback não é a métrica do D5 (essa sai no dispositivo)
        }
    }
}
