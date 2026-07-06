package org.opentoons.poc7.trama

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * poc-07 — envia uma linha de resultado à VPS por TCP puro (coletor na 6071). Serve à auditoria
 * de não-truque (4.3): a VPS registra o **IP de origem** da conexão. Se o iPhone estiver em
 * dados móveis (WiFi desligado), esse IP é o da operadora — ≠ do IP residencial do DEV —, o que
 * PROVA o egress celular ao IP público da VPS, sem depender do console USB.
 */
object Reporter {
    fun report(host: String, port: Int, text: String): Boolean = try {
        runBlocking {
            val sel = SelectorManager(Dispatchers.Default)
            val socket = aSocket(sel).tcp().connect(InetSocketAddress(host, port))
            val w = socket.openWriteChannel(autoFlush = true)
            w.writeFully(text.encodeToByteArray())
            w.flush()
            socket.close()
            sel.close()
            true
        }
    } catch (e: Throwable) {
        false
    }
}
