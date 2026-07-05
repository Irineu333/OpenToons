package org.opentoons.poc5.trama.wire

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Transporte TCP da PoC poc-02 (design D1): sockets bloqueantes do JDK, uma thread por
 * conexão. Sem framework — o tráfego da OpenToons é todo request/response com poucas
 * conexões simultâneas por nó, então o modelo simples basta e mantém o TLS do E1a
 * trivial (SSLSocket é drop-in sobre Socket).
 */
object Frames {
    /** Limite de frame: capítulos são servidos em blocos; nada legítimo passa disso. */
    const val MAX_FRAME = 4 * 1024 * 1024

    fun write(out: OutputStream, payload: ByteArray) {
        val data = DataOutputStream(out)
        data.writeInt(payload.size)
        data.write(payload)
        data.flush()
    }

    /** @return o payload, ou null em EOF limpo entre frames. */
    fun read(input: InputStream): ByteArray? {
        val data = DataInputStream(input)
        val len = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        require(len in 0..MAX_FRAME) { "frame de $len bytes fora do limite" }
        val payload = ByteArray(len)
        data.readFully(payload)
        return payload
    }
}

/** Uma conexão com IO de frames — implementada sobre Socket puro ou sobre o canal seguro do E1. */
interface FrameConnection : Closeable {
    val remoteDescription: String
    fun send(payload: ByteArray)
    /** Bloqueia até o próximo frame; null em EOF. */
    fun receive(): ByteArray?
}

class SocketFrameConnection(private val socket: Socket) : FrameConnection {
    private val input = socket.getInputStream()
    private val output = socket.getOutputStream()

    override val remoteDescription: String get() = socket.remoteSocketAddress.toString()
    override fun send(payload: ByteArray) = synchronized(output) { Frames.write(output, payload) }
    override fun receive(): ByteArray? = Frames.read(input)
    override fun close() = socket.close()
}

/** Servidor TCP: uma thread de accept + uma thread por conexão. */
class TcpServer(
    port: Int,
    private val handler: (FrameConnection) -> Unit,
) : Closeable {
    private val serverSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port))
    }
    private val closed = AtomicBoolean(false)

    val port: Int get() = serverSocket.localPort

    init {
        thread(isDaemon = true, name = "poc5-trama-accept-$port") {
            while (!closed.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    if (closed.get()) return@thread else continue
                }
                thread(isDaemon = true, name = "poc5-trama-conn-${socket.remoteSocketAddress}") {
                    socket.use { s -> runCatching { handler(SocketFrameConnection(s)) } }
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        serverSocket.close()
    }
}

object TcpClient {
    /**
     * Dial TCP. Em clearnet ([proxy] nulo) é o dial direto do poc-02. Em modo anônimo
     * (poc-05, D4) recebe um `Proxy(SOCKS, host:9050)` do daemon Tor local e disca com
     * endereço NÃO-RESOLVIDO ([InetSocketAddress.createUnresolved]) — o gatilho de
     * SOCKS5h: o JDK entrega o hostname/onion CRU ao proxy, que resolve DENTRO do circuito.
     * Nenhuma resolução de nome sai da máquina (a armadilha de DNS do D4). Um `.onion` não
     * resolve localmente por construção — reforça a garantia. O timeout é folgado no modo
     * anônimo: o estabelecimento do circuito adiciona segundos (limiar refixado D7).
     */
    fun dial(
        host: String,
        port: Int,
        timeoutMs: Int = 5_000,
        proxy: java.net.Proxy? = null,
    ): SocketFrameConnection {
        val socket = if (proxy != null) Socket(proxy) else Socket()
        val endpoint = if (proxy != null) {
            InetSocketAddress.createUnresolved(host, port) // SOCKS5h: resolução remota
        } else {
            InetSocketAddress(host, port)
        }
        socket.connect(endpoint, timeoutMs)
        return SocketFrameConnection(socket)
    }
}
