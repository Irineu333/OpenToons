package org.opentoons.poc2.rpc

import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.noise.NoiseChannel
import org.opentoons.poc2.tls.TlsChannel
import org.opentoons.poc2.tls.TlsIdentity
import org.opentoons.poc2.transport.TcpClient
import org.opentoons.poc2.transport.TcpServer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 6.2 — download de capítulo com verificação sobre o canal seguro do E1 (os DOIS canais,
 * já que o RPC é agnóstico), reconstruindo o mesmo capítulo de 3 blocos do poc-01.
 */
class ChapterFetchTest {

    private val publisherId = NodeIdentity.generate()
    private val clientId = NodeIdentity.generate()
    private val pages = listOf(
        ByteArray(256 * 1024) { (it % 251).toByte() },  // "página" 1
        ByteArray(256 * 1024) { (it % 241).toByte() },  // "página" 2
        ByteArray(256 * 1024) { (it % 239).toByte() },  // "página" 3
    )
    private val chapterId = "opentoons/serie-teste/cap-001"

    private fun publisher(): ChapterService.Publisher =
        ChapterService.Publisher(publisherId).also { it.publishChapter(chapterId, seq = 7, pages = pages) }

    @Test
    fun `download e verificacao de capitulo sobre Noise`() {
        val pub = publisher()
        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, publisherId)
            RpcPeer(secure, pub::handle).use { it.awaitClose() }
        }.use { server ->
            val secure = NoiseChannel.clientHandshake(
                TcpClient.dial("127.0.0.1", server.port), clientId, publisherId.publicKey,
            )
            RpcPeer(secure).use { rpc ->
                val chapter = ChapterService.Fetcher(rpc, publisherId.publicKey).fetchChapter(chapterId)
                assertContentEquals(pages[0], chapter[0])
                assertContentEquals(pages[1], chapter[1])
                assertContentEquals(pages[2], chapter[2])
            }
        }
    }

    @Test
    fun `download e verificacao de capitulo sobre TLS`() {
        val pub = publisher()
        TlsChannel.Server(0, TlsIdentity.boundCert(publisherId)) { conn, _ ->
            RpcPeer(conn, pub::handle).use { it.awaitClose() }
        }.use { server ->
            val (conn, _) = TlsChannel.dial(
                "127.0.0.1", server.port, TlsIdentity.boundCert(clientId), publisherId.publicKey,
            )
            RpcPeer(conn).use { rpc ->
                val chapter = ChapterService.Fetcher(rpc, publisherId.publicKey).fetchChapter(chapterId)
                pages.zip(chapter).forEach { (expected, actual) -> assertContentEquals(expected, actual) }
            }
        }
    }

    // 6.2/7.3 (mecanismo) — bloco adulterado é rejeitado pelo hash do manifesto
    @Test
    fun `bloco adulterado e rejeitado`() {
        val pub = publisher()
        val corruptingHandler = { type: Byte, body: ByteArray ->
            val (t, resp) = pub.handle(type, body)
            if (type == RpcTypes.GET_BLOCK) {
                val blob = RpcCodec.decode(BlobResponse.serializer(), resp)
                blob.bytes[100] = (blob.bytes[100].toInt() xor 1).toByte()
                t to RpcCodec.encode(BlobResponse.serializer(), blob)
            } else {
                t to resp
            }
        }
        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, publisherId)
            RpcPeer(secure, corruptingHandler).use { it.awaitClose() }
        }.use { server ->
            val secure = NoiseChannel.clientHandshake(
                TcpClient.dial("127.0.0.1", server.port), clientId, publisherId.publicKey,
            )
            RpcPeer(secure).use { rpc ->
                val failure = assertFailsWith<ChapterService.VerificationException> {
                    ChapterService.Fetcher(rpc, publisherId.publicKey).fetchChapter(chapterId)
                }
                assertTrue(failure.message!!.contains("adulterado"))
            }
        }
    }

    // 6.2/7.3 (mecanismo) — manifesto com assinatura inválida é rejeitado
    @Test
    fun `manifesto com assinatura de outra chave e rejeitado`() {
        val impostor = NodeIdentity.generate()
        // impostor publica o MESMO capítulo assinado com a chave dele
        val pub = ChapterService.Publisher(impostor).also { it.publishChapter(chapterId, 7, pages) }
        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, impostor)
            RpcPeer(secure, pub::handle).use { it.awaitClose() }
        }.use { server ->
            val secure = NoiseChannel.clientHandshake(
                TcpClient.dial("127.0.0.1", server.port), clientId, impostor.publicKey,
            )
            RpcPeer(secure).use { rpc ->
                // o cliente espera o capítulo do PUBLICADOR legítimo
                assertFailsWith<ChapterService.VerificationException> {
                    ChapterService.Fetcher(rpc, publisherId.publicKey).fetchChapter(chapterId)
                }
            }
        }
    }
}
