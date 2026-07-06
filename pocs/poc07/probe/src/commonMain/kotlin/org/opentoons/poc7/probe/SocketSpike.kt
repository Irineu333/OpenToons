package org.opentoons.poc7.probe

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.time.TimeSource

/**
 * Spike 2.3 — de-risca o dial TCP em Kotlin/Native com **ktor-network** (o candidato
 * cross-platform ao `java.net.Socket` da Trama, que não existe no Native). Disca um host:port,
 * envia um frame `[len BE][payload]` e lê o eco de volta (a VPS ecoa bytes crus). Aferição
 * (D6): o payload que volta tem de ser byte-a-byte igual ao enviado. Mede connect e RTT.
 *
 * O mesmo código roda no host (JVM) e no device (iosArm64) — é o teste de que a stack de socket
 * do ktor fecha em Native, o que decide se a Trama pode usar ktor-network em `commonMain`.
 */
object SocketSpike {
    fun run(host: String, port: Int): String = try {
        runBlocking {
            val sel = SelectorManager(Dispatchers.Default)
            val t0 = TimeSource.Monotonic.markNow()
            val socket = aSocket(sel).tcp().connect(host, port)
            val connectMs = t0.elapsedNow().inWholeMilliseconds

            val w = socket.openWriteChannel(autoFlush = true)
            val r = socket.openReadChannel()
            val payload = "POC07-SOCK/$host:$port/opentoons".encodeToByteArray()

            val t1 = TimeSource.Monotonic.markNow()
            w.writeInt(payload.size)
            w.writeFully(payload, 0, payload.size)
            w.flush()

            val len = r.readInt()
            val echoed = ByteArray(len)
            r.readFully(echoed, 0, len)
            val rttMs = t1.elapsedNow().inWholeMilliseconds

            socket.close()
            sel.close()

            val ok = echoed.contentEquals(payload)
            if (ok) "POC07-SOCK PASS host=$host:$port connect=${connectMs}ms rtt=${rttMs}ms bytes=$len"
            else "POC07-SOCK FAIL eco divergente (env=${payload.size} recv=$len)"
        }
    } catch (e: Throwable) {
        "POC07-SOCK FAIL ${e::class.simpleName}: ${e.message}"
    }
}
