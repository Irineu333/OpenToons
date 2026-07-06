package org.opentoons.poc7.trama.wire

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * poc-07 — transporte de frames PORTADO para coroutines/ktor-network (substitui os sockets
 * bloqueantes + threads do `java.net` do poc-06). Frame = `[4 B len BE][payload]`. IO suspensa;
 * o full node e o client falam só com [FrameConnection]/[FrameTransport] — Noise, RPC e
 * membership são indiferentes ao transporte.
 */
object Frames {
    const val MAX_FRAME = 4 * 1024 * 1024

    suspend fun write(out: ByteWriteChannel, payload: ByteArray) {
        out.writeInt(payload.size)
        out.writeFully(payload)
        out.flush()
    }

    /** @return o payload, ou null em EOF limpo entre frames. */
    suspend fun read(input: ByteReadChannel): ByteArray? {
        val len = try {
            input.readInt()
        } catch (_: Throwable) {
            return null // EOF/fechado entre frames
        }
        require(len in 0..MAX_FRAME) { "frame de $len bytes fora do limite" }
        val payload = ByteArray(len)
        input.readFully(payload)
        return payload
    }
}

/** Uma conexão com IO de frames — sobre socket puro ou sobre o canal seguro Noise. */
interface FrameConnection {
    val remoteDescription: String
    suspend fun send(payload: ByteArray)
    /** Suspende até o próximo frame; null em EOF. */
    suspend fun receive(): ByteArray?
    fun close()
}

internal class SocketFrameConnection(private val socket: Socket) : FrameConnection {
    private val input = socket.openReadChannel()
    private val output = socket.openWriteChannel(autoFlush = false)
    private val sendLock = Mutex()

    override val remoteDescription: String get() = socket.remoteAddress.toString()
    override suspend fun send(payload: ByteArray) = sendLock.withLock { Frames.write(output, payload) }
    override suspend fun receive(): ByteArray? = Frames.read(input)
    override fun close() { runCatching { socket.close() } }
}

/**
 * poc-07 — o SEAM de transporte. Endereço = string OPACA: `host:port` (TCP) ou destination
 * base64 (I2P, célula 3). O transporte possui um escopo de coroutines para os laços de accept.
 */
interface FrameTransport {
    val localAddress: String
    suspend fun dial(address: String, timeoutMs: Long = 45_000): FrameConnection
    /** Passa a aceitar conexões, cada uma no [handler] (full node). */
    fun listen(handler: suspend (FrameConnection) -> Unit)
    fun close()
}

/** Transporte TCP sobre ktor-network: endereço = `host:port`. Baseline clearnet + TCK. */
class TcpTransport(
    private val bindPort: Int,
    private val advertisedAddress: String? = null,
) : FrameTransport {
    private val selector = SelectorManager(Dispatchers.Default)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: ServerSocket? = null
    private var boundPort: Int = bindPort

    override val localAddress: String
        get() = advertisedAddress ?: "127.0.0.1:$boundPort"

    override suspend fun dial(address: String, timeoutMs: Long): FrameConnection {
        val (host, port) = address.split(":", limit = 2)
        val socket = withTimeout(timeoutMs) {
            aSocket(selector).tcp().connect(InetSocketAddress(host, port.toInt()))
        }
        return SocketFrameConnection(socket)
    }

    override fun listen(handler: suspend (FrameConnection) -> Unit) {
        // bind é suspenso, mas start()/boundPort são síncronos: liga sob runBlocking e só então
        // lança o laço de accept no escopo (o full node lê boundPort logo após listen()).
        val srv = runBlocking { aSocket(selector).tcp().bind(InetSocketAddress("0.0.0.0", bindPort)) }
        server = srv
        boundPort = (srv.localAddress as InetSocketAddress).port
        scope.launch {
            while (true) {
                val socket = try {
                    srv.accept()
                } catch (_: Throwable) {
                    break // servidor fechado
                }
                launch {
                    val conn = SocketFrameConnection(socket)
                    runCatching { handler(conn) }
                    conn.close()
                }
            }
        }
    }

    override fun close() {
        runCatching { server?.close() }
        runCatching { selector.close() }
    }
}
