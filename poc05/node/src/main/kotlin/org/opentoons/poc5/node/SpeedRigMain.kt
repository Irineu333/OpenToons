package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.Provider
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 — VELOCIDADE DE TRANSFERÊNCIA: clearnet × Tor, a mesma operação (push de 768 KiB),
 * isolando o transporte. Mede push FRIO (circuito/conexão nova) e QUENTE (reuso), reporta
 * throughput efetivo (KiB/s) e o fator de lentidão do Tor. Dado honesto do custo do anonimato.
 *
 * uso: SpeedRigMainKt --backend=trama|libp2p --onion-r=<host>.onion [--socks=9050] [--n=4]
 */
object SpeedRig {

    private const val PAYLOAD_KIB = 768.0 // 3 × 256 KiB (TckVectors)

    fun run(args: Array<String>) {
        val opts = NodeRunner.parseArgs(args)
        val backend = opts["backend"] ?: error("--backend")
        val onion = opts["onion-r"] ?: error("--onion-r")
        val rPort = opts["r-port"]?.toInt() ?: 4100
        val socks = opts["socks"]?.toInt() ?: 9050
        val n = opts["n"]?.toInt() ?: 4

        val r = if (backend == "trama") TramaBackend.fullNode("poc5-replicador-R", AnnounceTuning())
        else Libp2pBackend.fullNode("poc5-replicador-R", AnnounceTuning())
        r.serve(MemoryBlockstore())
        r.acceptPushes(TckVectors.contentKeys.idHex)
        r.start(ListenSpec(rPort), emptyList())

        val prep = TckVectors.prepared()
        fun push(client: P2pBackend, target: Provider): Long {
            val t0 = System.currentTimeMillis()
            client.push(target, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
            return System.currentTimeMillis() - t0
        }
        fun tput(ms: Long) = "%.1f KiB/s".format(PAYLOAD_KIB / (ms / 1000.0))

        println("=== velocidade de transferência (push 768 KiB) — backend=$backend ===")

        // --- CLEARNET: publicador direto (sem Tor) ao endereço LOOPBACK de R (multiaddr /ip4
        // correto via selfProvider) — best-case, isola o transporte da variação de rede. ---
        val clearTarget = r.selfProvider()
        val clearPub: P2pBackend = if (backend == "trama") TramaBackend.client() else Libp2pBackend.client()
        val clear = (1..n).map { push(clearPub, clearTarget) }
        clearPub.close()
        val clearWarm = clear.drop(1)
        println("CLEARNET: frio=${clear.first()} ms | quente(mediana)=${median(clearWarm)} ms | tput=${tput(median(clearWarm))}")

        // --- TOR: publicador anônimo pelo onion (mesmo payload) ---
        val torTarget = if (backend == "trama") TramaBackend.onionProvider(r.idHex, onion, rPort)
        else Libp2pBackend.onionProvider(r.idHex, onion, rPort)
        val torPub: P2pBackend = if (backend == "trama") TramaBackend.client(AnonymityConfig.tor(socksPort = socks))
        else Libp2pBackend.client(AnonymityConfig.tor(socksPort = socks))
        val tor = (1..n).map { push(torPub, torTarget) }
        torPub.close()
        val torWarm = tor.drop(1)
        println("TOR    : frio=${tor.first()} ms | quente(mediana)=${median(torWarm)} ms | tput=${tput(median(torWarm))}")
        println("todas as amostras — clearnet=$clear ms | tor=$tor ms")

        r.stop()
        val slowCold = tor.first().toDouble() / clear.first()
        val slowWarm = median(torWarm).toDouble() / median(clearWarm).coerceAtLeast(1)
        println()
        println("LENTIDÃO DO TOR: frio ${"%.0f".format(slowCold)}× | quente ${"%.0f".format(slowWarm)}× " +
            "(clearnet ${tput(median(clearWarm))} → Tor ${tput(median(torWarm))})")
    }

    private fun median(xs: List<Long>): Long =
        if (xs.isEmpty()) 0 else xs.sorted().let { it[it.size / 2] }
}

fun main(args: Array<String>) = SpeedRig.run(args)
