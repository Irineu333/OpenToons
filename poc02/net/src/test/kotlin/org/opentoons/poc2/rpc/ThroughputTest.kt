package org.opentoons.poc2.rpc

import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.noise.NoiseChannel
import org.opentoons.poc2.tls.TlsChannel
import org.opentoons.poc2.tls.TlsIdentity
import org.opentoons.poc2.transport.FrameConnection
import org.opentoons.poc2.transport.TcpClient
import org.opentoons.poc2.transport.TcpServer
import java.util.concurrent.TimeUnit
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * 6.3 — throughput do capítulo de 3 blocos em loopback pelos dois canais, e observação
 * de head-of-line blocking com downloads concorrentes (design D4: mitigar só se aparecer).
 * Números de rede real saem do E4/E5 (dispositivo). O relatório do poc-01 não publicou
 * throughput do nabu/bitswap — a comparação lado a lado exige re-medição no dispositivo.
 */
class ThroughputTest {

    private val publisherId = NodeIdentity.generate()
    private val clientId = NodeIdentity.generate()
    private val pages = List(3) { p -> ByteArray(256 * 1024) { ((it + p) % 251).toByte() } }
    private val chapterId = "opentoons/serie-teste/cap-001"

    private fun servePublisher(secureFactory: (FrameConnection) -> FrameConnection): TcpServer {
        val pub = ChapterService.Publisher(publisherId).also { it.publishChapter(chapterId, 7, pages) }
        return TcpServer(0) { raw ->
            RpcPeer(secureFactory(raw), pub::handle).use { it.awaitClose() }
        }
    }

    private fun measure(channel: String, dial: (Int) -> FrameConnection) {
        servePublisher({ raw ->
            if (channel == "noise") NoiseChannel.serverHandshake(raw, publisherId) else raw
        }).use { server ->
            RpcPeer(dial(server.port)).use { rpc ->
                val fetcher = ChapterService.Fetcher(rpc, publisherId.publicKey)
                fetcher.fetchChapter(chapterId) // aquecimento (JIT + caches)
                val nanos = measureNanoTime { fetcher.fetchChapter(chapterId) }
                val totalKb = pages.sumOf { it.size } / 1024.0
                val ms = nanos / 1_000_000.0
                val mbps = totalKb / 1024.0 / (nanos / 1e9)
                println("poc2-e2-throughput: canal=$channel capitulo_768KiB ms=${"%.1f".format(ms)} MB/s=${"%.1f".format(mbps)}")
            }
        }
    }

    @Test
    fun `throughput do capitulo por Noise em loopback`() {
        measure("noise") { port ->
            NoiseChannel.clientHandshake(TcpClient.dial("127.0.0.1", port), clientId, publisherId.publicKey)
        }
    }

    @Test
    fun `throughput do capitulo por TCP puro em loopback (baseline)`() {
        measure("tcp") { port -> TcpClient.dial("127.0.0.1", port) }
    }

    @Test
    fun `throughput do capitulo por TLS em loopback`() {
        val pub = ChapterService.Publisher(publisherId).also { it.publishChapter(chapterId, 7, pages) }
        TlsChannel.Server(0, TlsIdentity.boundCert(publisherId)) { conn, _ ->
            RpcPeer(conn, pub::handle).use { it.awaitClose() }
        }.use { server ->
            val (conn, _) = TlsChannel.dial(
                "127.0.0.1", server.port, TlsIdentity.boundCert(clientId), publisherId.publicKey,
            )
            RpcPeer(conn).use { rpc ->
                val fetcher = ChapterService.Fetcher(rpc, publisherId.publicKey)
                fetcher.fetchChapter(chapterId)
                val nanos = measureNanoTime { fetcher.fetchChapter(chapterId) }
                println(
                    "poc2-e2-throughput: canal=tls capitulo_768KiB ms=${"%.1f".format(nanos / 1_000_000.0)} " +
                        "MB/s=${"%.1f".format(768.0 / 1024.0 / (nanos / 1e9))}",
                )
            }
        }
    }

    // D4 — head-of-line: um bloco grande na frente atrasa quanto um pequeno concorrente?
    @Test
    fun `head-of-line blocking observado com bloco grande e pequeno concorrentes`() {
        val big = ByteArray(4 * 1024 * 1024 - 64) { it.toByte() } // ~4 MiB (limite do frame)
        val small = ByteArray(1024) { it.toByte() }
        val pub = ChapterService.Publisher(publisherId)
        pub.publishChapter("obra/grande", 1, listOf(big))
        pub.publishChapter("obra/pequena", 1, listOf(small))
        val bigCid = ChapterService.cidOf(big)
        val smallCid = ChapterService.cidOf(small)

        TcpServer(0) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, publisherId)
            RpcPeer(secure, pub::handle).use { it.awaitClose() }
        }.use { server ->
            val secure = NoiseChannel.clientHandshake(
                TcpClient.dial("127.0.0.1", server.port), clientId, publisherId.publicKey,
            )
            RpcPeer(secure).use { rpc ->
                fun smallAlone(): Double = measureNanoTime {
                    rpc.call(RpcTypes.GET_BLOCK, RpcCodec.encode(GetBlockRequest.serializer(), GetBlockRequest(smallCid)))
                } / 1e6

                val alone = smallAlone()
                // dispara o grande primeiro e o pequeno em seguida na MESMA conexão
                val bigFuture = rpc.callAsync(RpcTypes.GET_BLOCK, RpcCodec.encode(GetBlockRequest.serializer(), GetBlockRequest(bigCid)))
                val contended = smallAlone()
                bigFuture.get(30, TimeUnit.SECONDS)
                println(
                    "poc2-e2-hol: pequeno_sozinho_ms=${"%.2f".format(alone)} " +
                        "pequeno_atras_de_4MiB_ms=${"%.2f".format(contended)}",
                )
            }
        }
    }
}
