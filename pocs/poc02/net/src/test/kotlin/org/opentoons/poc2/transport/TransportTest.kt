package org.opentoons.poc2.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TransportTest {

    // 1.3 — loopback JVM↔JVM com frames length-prefixed
    @Test
    fun `loopback eco de frames preserva conteudo e fronteiras`() {
        TcpServer(0) { conn ->
            while (true) {
                val frame = conn.receive() ?: break
                conn.send(frame)
            }
        }.use { server ->
            TcpClient.dial("127.0.0.1", server.port).use { client ->
                val frames = listOf(
                    ByteArray(0),
                    "olá opentoons".toByteArray(),
                    ByteArray(1_000_000) { (it % 251).toByte() },
                )
                frames.forEach { client.send(it) }
                frames.forEach { expected ->
                    assertContentEquals(expected, client.receive())
                }
            }
        }
    }

    @Test
    fun `eof limpo retorna null em vez de exception`() {
        TcpServer(0) { conn ->
            conn.send("um".toByteArray())
            conn.close()
        }.use { server ->
            TcpClient.dial("127.0.0.1", server.port).use { client ->
                assertEquals("um", String(client.receive()!!))
                assertNull(client.receive())
            }
        }
    }

    @Test
    fun `frame acima do limite e rejeitado sem alocar`() {
        TcpServer(0) { conn ->
            // envia um header anunciando 1 GiB sem payload
            conn.send(ByteArray(0)) // garante handshake do accept
        }.use { server ->
            val raw = java.net.Socket("127.0.0.1", server.port)
            raw.use {
                val out = java.io.DataOutputStream(it.getOutputStream())
                out.writeInt(Int.MAX_VALUE)
                out.flush()
            }
            // do lado de cá, validamos a guarda diretamente
            val evil = java.io.ByteArrayInputStream(
                java.nio.ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array(),
            )
            assertFailsWith<IllegalArgumentException> { Frames.read(evil) }
        }
    }
}
