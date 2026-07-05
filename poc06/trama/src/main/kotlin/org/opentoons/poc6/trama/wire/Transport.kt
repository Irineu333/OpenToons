package org.opentoons.poc6.trama.wire

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
        thread(isDaemon = true, name = "poc6-trama-accept-$port") {
            while (!closed.get()) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    if (closed.get()) return@thread else continue
                }
                thread(isDaemon = true, name = "poc6-trama-conn-${socket.remoteSocketAddress}") {
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
    /** Dial TCP direto (clearnet) — usado só na comparação de baseline e no TCK sobre loopback. */
    fun dial(host: String, port: Int, timeoutMs: Int = 5_000): SocketFrameConnection {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        return SocketFrameConnection(socket)
    }
}

/**
 * poc-06 — o SEAM de transporte (design D2): a única variável que muda entre "rede clearnet"
 * e "rede nativamente anônima sobre I2P". O full node e o client falam SÓ com esta interface;
 * Noise, RPC, membership e push são indiferentes a QUAL transporte carrega os frames. Um
 * endereço é uma string OPACA: `host:port` no TCP, uma **destination** base64 no I2P — o
 * "NAT dissolvido" do D0 é exatamente isto: o endereço deixa de ser IP:porta.
 */
interface FrameTransport : AutoCloseable {
    /** Nosso endereço discável (o que anunciamos): `host:port` (TCP) ou destination (I2P). */
    val localAddress: String

    /** Disca um endereço opaco e devolve o stream. Timeout folgado: túnel I2P leva segundos. */
    fun dial(address: String, timeoutMs: Int = 45_000): FrameConnection

    /** Passa a aceitar conexões de entrada, cada uma no [handler] (full node). */
    fun listen(handler: (FrameConnection) -> Unit)
}

/** Transporte TCP: endereço = `host:port`. Baseline clearnet e TCK sobre loopback. */
class TcpTransport(
    private val bindPort: Int,
    private val advertisedAddress: String? = null,
) : FrameTransport {
    @Volatile private var server: TcpServer? = null
    @Volatile private var boundPort: Int = bindPort

    override val localAddress: String
        get() = advertisedAddress ?: "127.0.0.1:$boundPort"

    override fun dial(address: String, timeoutMs: Int): FrameConnection {
        val (host, port) = address.split(":", limit = 2)
        return TcpClient.dial(host, port.toInt(), timeoutMs)
    }

    override fun listen(handler: (FrameConnection) -> Unit) {
        val srv = TcpServer(bindPort, handler)
        server = srv
        boundPort = srv.port
    }

    override fun close() {
        server?.close()
    }
}

/**
 * poc-06 — transporte I2P sobre uma [SamSession] (design D1): endereço = destination base64.
 * [dial] = STREAM CONNECT por túnel; [listen] = laço de STREAM ACCEPT. O mesmo código roda em
 * desktop e Android (fala SAM ao router local) — zero branch de app. É AQUI que o "NAT
 * dissolvido / anonimato de rede para todos os papéis" (D0) vira execução.
 */
class SamTransport(private val session: SamSession) : FrameTransport {
    @Volatile private var server: SamServer? = null

    override val localAddress: String get() = session.myDestination

    override fun dial(address: String, timeoutMs: Int): FrameConnection =
        session.connect(address, timeoutMs.toLong())

    override fun listen(handler: (FrameConnection) -> Unit) {
        server = SamServer(session, handler = handler)
    }

    override fun close() {
        server?.close()
        session.close()
    }
}
