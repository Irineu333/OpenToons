package org.opentoons.poc2.rpc

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import org.opentoons.poc2.transport.TcpClient
import org.opentoons.poc2.transport.TcpServer
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RpcTest {

    // 6.1 — racional da escolha CBOR vs protobuf (questão Q4): medição direta
    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `Q4 - tamanhos CBOR vs protobuf para os corpos do wire`() {
        val req = GetBlockRequest("a".repeat(64))
        val blob = BlobResponse(ByteArray(65536) { it.toByte() })
        val cborReq = RpcCodec.encode(GetBlockRequest.serializer(), req).size
        val protoReq = ProtoBuf.encodeToByteArray(GetBlockRequest.serializer(), req).size
        val cborBlob = RpcCodec.encode(BlobResponse.serializer(), blob).size
        val protoBlob = ProtoBuf.encodeToByteArray(BlobResponse.serializer(), blob).size
        println("poc2-e2-q4: req cbor=$cborReq proto=$protoReq | blob64k cbor=$cborBlob proto=$protoBlob")
        // corpos dominados por bytes de bloco: a diferença precisa ser desprezível (< 1%)
        assertTrue(cborBlob - protoBlob in -655..655)
    }

    // 6.1 — requisições concorrentes numa conexão, respostas correlacionadas fora de ordem
    @Test
    fun `respostas fora de ordem sao correlacionadas pelo request-id`() {
        // handler devolve o eco, mas atrasa MAIS as requisições que chegam PRIMEIRO —
        // força respostas em ordem inversa à das requisições
        val handler = { type: Byte, body: ByteArray ->
            val seq = ByteBuffer.wrap(body).int
            Thread.sleep(((5 - seq) * 40).toLong())
            type to body
        }
        TcpServer(0) { conn ->
            RpcPeer(conn, handler).use { it.awaitClose() }
        }.use { server ->
            val client = RpcPeer(TcpClient.dial("127.0.0.1", server.port))
            client.use {
                val futures = (0 until 5).map { seq ->
                    seq to it.callAsync(1, ByteBuffer.allocate(4).putInt(seq).array())
                }
                futures.forEach { (seq, future) ->
                    val (_, body) = future.get(10, java.util.concurrent.TimeUnit.SECONDS)
                    assertEquals(seq, ByteBuffer.wrap(body).int, "resposta trocada entre requisições!")
                }
            }
        }
    }

    @Test
    fun `erro do handler vira RpcException no chamador sem derrubar a conexao`() {
        val handler = { type: Byte, body: ByteArray ->
            if (type.toInt() == 9) throw RpcException("não sei fazer isso")
            type to body
        }
        TcpServer(0) { conn ->
            RpcPeer(conn, handler).use { it.awaitClose() }
        }.use { server ->
            RpcPeer(TcpClient.dial("127.0.0.1", server.port)).use { client ->
                assertFailsWith<RpcException> { client.call(9, ByteArray(0)) }
                // a conexão sobrevive ao erro
                val (_, body) = client.call(1, "ainda vivo".toByteArray())
                assertEquals("ainda vivo", String(body))
            }
        }
    }
}
