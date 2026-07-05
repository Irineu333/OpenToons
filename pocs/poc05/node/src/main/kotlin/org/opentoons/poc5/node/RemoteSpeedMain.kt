package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.Provider
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 (teste com VPS real) — roda no DESKTOP: mede a velocidade de PUSH de 768 KiB para R
 * numa VPS SEPARADA (internet real), CLEARNET × TOR. Mesma operação, mesma direção (upload),
 * ambos pela internet — a comparação simétrica e honesta que faltava.
 *
 * uso: RemoteSpeedMainKt --r-id=<hex> --r-clear=<ip:porta> --onion=<host>.onion:porta [--socks=9050] [--n=5]
 */
fun main(args: Array<String>) {
    val opts = NodeRunner.parseArgs(args)
    val rId = opts["r-id"] ?: error("--r-id")
    val rClear = opts["r-clear"] ?: error("--r-clear=<ip:porta>")
    val onion = opts["onion"] ?: error("--onion=<host>.onion:porta")
    val socks = opts["socks"]?.toInt() ?: 9050
    val n = opts["n"]?.toInt() ?: 5
    val kib = 768.0

    val prep = TckVectors.prepared()
    val clearTarget = Provider(rId, listOf(rClear))
    val torTarget = Provider(rId, listOf(onion))

    fun measure(label: String, client: P2pBackend, target: Provider): List<Long> {
        val times = ArrayList<Long>()
        repeat(n) { i ->
            val t0 = System.currentTimeMillis()
            client.push(target, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
            val dt = System.currentTimeMillis() - t0
            times.add(dt)
            println("$label[$i] push 768 KiB em ${dt} ms = ${"%.1f".format(kib / (dt / 1000.0))} KiB/s")
        }
        return times
    }
    fun med(xs: List<Long>) = xs.sorted()[xs.size / 2]
    fun tput(ms: Long) = "%.1f KiB/s".format(kib / (ms / 1000.0))

    println("=== PUSH desktop → VPS (internet real): clearnet × Tor ===")
    val clearPub = TramaBackend.client()
    val clear = measure("CLEARNET", clearPub, clearTarget)
    clearPub.close()

    val torPub = TramaBackend.client(AnonymityConfig.tor(socksPort = socks))
    val tor = measure("TOR     ", torPub, torTarget)
    torPub.close()

    val mc = med(clear.drop(1).ifEmpty { clear }); val mt = med(tor.drop(1).ifEmpty { tor })
    println()
    println("CLEARNET REAL (desktop→VPS, upload): mediana ${mc} ms = ${tput(mc)}")
    println("TOR (desktop→VPS onion, upload):     mediana ${mt} ms = ${tput(mt)}")
    println("LENTIDÃO DO TOR: ${"%.1f".format(mt.toDouble() / mc)}× (frio clear=${clear.first()}ms tor=${tor.first()}ms)")
}
