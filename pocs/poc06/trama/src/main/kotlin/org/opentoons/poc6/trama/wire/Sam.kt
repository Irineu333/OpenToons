package org.opentoons.poc6.trama.wire

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * poc-06 — transporte I2P via SAM v3 (design D1). É o ÚNICO código novo do instrumento: todo
 * o resto (Noise, RPC de frames, membership, push) roda IDÊNTICO ao poc-05 sobre a
 * [FrameConnection] que este arquivo produz — a mesma superfície que o [SocketFrameConnection]
 * do TCP oferece. Um nó fala SAM a um router I2P local: `SESSION CREATE STYLE=STREAM`
 * (session = destination), `STREAM CONNECT` para discar, `STREAM ACCEPT` para servir. O
 * endereço de rede vira a **destination** (base64 ~524 chars), opaca por contrato — nada de
 * IP:porta no wire da aplicação (o "NAT dissolvido" do D0).
 *
 * Derriscado por `poc06/rig/sam_spike.py` contra i2pd 2.60.0 real (bytes nos dois sentidos,
 * peer autenticado por destination). Dois achados codificados aqui: (1) `NAMING LOOKUP ME`
 * precisa correr no MESMO socket de controle da sessão; (2) o leaseSet do alvo leva alguns
 * segundos para publicar → o dial re-tenta com backoff (`CANT_REACH_PEER`).
 */
class SamException(message: String) : RuntimeException(message)

/** Lê uma linha (até '\n') SEM sobre-ler o stream — os bytes seguintes são frame de aplicação. */
private fun readLine(input: InputStream): String {
    val sb = StringBuilder()
    while (true) {
        val b = input.read()
        if (b < 0) {
            if (sb.isEmpty()) throw SamException("EOF do bridge SAM")
            break
        }
        if (b == '\n'.code) break
        if (b != '\r'.code) sb.append(b.toChar())
    }
    return sb.toString()
}

private fun samHandshake(socket: Socket) {
    val out = socket.getOutputStream()
    out.write("HELLO VERSION MIN=3.1 MAX=3.3\n".toByteArray())
    out.flush()
    val reply = readLine(socket.getInputStream())
    if (!reply.contains("RESULT=OK")) throw SamException("HELLO recusado: $reply")
}

private fun samValue(line: String, key: String): String? =
    line.split(" ").firstOrNull { it.startsWith("$key=") }?.substringAfter("=")

/**
 * Uma sessão SAM STREAM = um destination (a identidade de REDE do nó, distinta da identidade
 * Ed25519/Noise da aplicação). O socket de controle fica aberto pela vida da sessão. [destKey]
 * nulo → destination TRANSIENTE (efêmera, o client só-saída do ADR-0005); persistente → o full
 * node reusa a MESMA destination entre reinícios (endereço estável do bootstrap).
 */
class SamSession private constructor(
    private val samHost: String,
    private val samPort: Int,
    private val nick: String,
    private val control: Socket,
    /** Destination pública (base64) desta sessão — o endereço discável do nó. */
    val myDestination: String,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)

    private fun newSamSocket(): Socket {
        val s = Socket()
        s.connect(InetSocketAddress(samHost, samPort), 10_000)
        samHandshake(s)
        return s
    }

    /**
     * Disca a [destination] por dentro de túneis I2P. O leaseSet do alvo pode ainda não ter
     * propagado logo após ele subir → re-tenta com backoff até [deadlineMs] (limiar D7).
     */
    fun connect(destination: String, deadlineMs: Long = 45_000): FrameConnection {
        val deadline = System.currentTimeMillis() + deadlineMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            val s = newSamSocket()
            val out = s.getOutputStream()
            out.write("STREAM CONNECT ID=$nick DESTINATION=$destination SILENT=false\n".toByteArray())
            out.flush()
            val status = readLine(s.getInputStream())
            if (status.contains("RESULT=OK")) {
                return SocketFrameConnection(s) // o socket agora É o stream I2P cru
            }
            last = status
            runCatching { s.close() }
            if (status.contains("CANT_REACH_PEER") || status.contains("LeaseSet")) {
                Thread.sleep(3_000) // leaseSet ainda publicando
            } else {
                throw SamException("STREAM CONNECT falhou: $status")
            }
        }
        throw SamException("STREAM CONNECT esgotou o prazo: $last")
    }

    /**
     * Bloqueia até UM peer conectar (STREAM ACCEPT), devolvendo a conexão já posicionada após
     * a linha de destination do peer. Cada ACCEPT serve uma conexão; o servidor re-arma.
     */
    fun accept(): FrameConnection {
        val s = newSamSocket()
        val out = s.getOutputStream()
        out.write("STREAM ACCEPT ID=$nick SILENT=false\n".toByteArray())
        out.flush()
        val input = s.getInputStream()
        val status = readLine(input)
        if (!status.contains("RESULT=OK")) {
            runCatching { s.close() }
            throw SamException("STREAM ACCEPT falhou: $status")
        }
        // primeira linha após OK = destination do peer que conectou (SILENT=false)
        readLine(input) // consumida; a autenticação real é o handshake Noise por cima
        return SocketFrameConnection(s)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { control.close() }
    }

    companion object {
        /**
         * Cria a sessão. [destKey] = chave privada base64 do destination (persistência); nulo
         * → TRANSIENT. Túneis default (3 hops cada lado) — a PoC mede o custo REAL do anonimato,
         * sem encurtar caminho. Bloqueia até a sessão estar pronta (SESSION STATUS RESULT=OK).
         */
        fun create(
            samHost: String,
            samPort: Int,
            nick: String,
            destKey: String? = null,
            inbound: Int = 2,
            outbound: Int = 2,
        ): SamSession {
            val control = Socket()
            control.connect(InetSocketAddress(samHost, samPort), 10_000)
            samHandshake(control)
            val dest = destKey ?: "TRANSIENT"
            val out = control.getOutputStream()
            out.write(
                ("SESSION CREATE STYLE=STREAM ID=$nick DESTINATION=$dest " +
                    "SIGNATURE_TYPE=EdDSA_SHA512_Ed25519 " +
                    "inbound.quantity=$inbound outbound.quantity=$outbound\n").toByteArray(),
            )
            out.flush()
            val status = readLine(control.getInputStream())
            if (!status.contains("RESULT=OK")) {
                runCatching { control.close() }
                throw SamException("SESSION CREATE falhou: $status")
            }
            // NAMING LOOKUP ME no MESMO socket de controle (achado do spike)
            out.write("NAMING LOOKUP NAME=ME\n".toByteArray())
            out.flush()
            val lookup = readLine(control.getInputStream())
            val myDest = samValue(lookup, "VALUE")
                ?: throw SamException("NAMING LOOKUP ME sem VALUE: $lookup")
            return SamSession(samHost, samPort, nick, control, myDest)
        }

        /** Gera um par de destination persistente (base64 priv, base64 pub) via `DEST GENERATE`. */
        fun generateDestination(samHost: String, samPort: Int): Pair<String, String> {
            val s = Socket()
            s.connect(InetSocketAddress(samHost, samPort), 10_000)
            samHandshake(s)
            val out = s.getOutputStream()
            out.write("DEST GENERATE SIGNATURE_TYPE=EdDSA_SHA512_Ed25519\n".toByteArray())
            out.flush()
            val reply = readLine(s.getInputStream())
            runCatching { s.close() }
            val priv = samValue(reply, "PRIV") ?: throw SamException("DEST GENERATE sem PRIV: $reply")
            val pub = samValue(reply, "PUB") ?: throw SamException("DEST GENERATE sem PUB: $reply")
            return priv to pub
        }
    }
}

/**
 * Servidor I2P: uma sessão SAM + N threads de ACCEPT re-armadas (análogo do [TcpServer], que
 * usa `ServerSocket.accept()`). Cada conexão aceita roda o [handler] numa thread própria; a
 * thread de accept re-arma imediatamente para não perder conexões concorrentes.
 */
class SamServer(
    private val session: SamSession,
    acceptors: Int = 3,
    private val handler: (FrameConnection) -> Unit,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    val destination: String get() = session.myDestination

    init {
        repeat(acceptors) { i ->
            thread(isDaemon = true, name = "poc6-sam-accept-$i") {
                while (!closed.get()) {
                    val conn = try {
                        session.accept()
                    } catch (_: Exception) {
                        if (closed.get()) return@thread
                        Thread.sleep(500)
                        continue
                    }
                    thread(isDaemon = true, name = "poc6-sam-conn") {
                        conn.use { runCatching { handler(it) } }
                    }
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        session.close()
    }
}
