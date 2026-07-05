package org.opentoons.poc6.node

import org.opentoons.poc6.api.AnnounceTuning
import org.opentoons.poc6.api.BootstrapAddr
import org.opentoons.poc6.api.ListenSpec
import org.opentoons.poc6.api.MemoryBlockstore
import org.opentoons.poc6.api.tck.TckVectors
import org.opentoons.poc6.trama.TramaBackend
import org.opentoons.poc6.trama.wire.SamSession
import java.io.File

/**
 * poc-06 — R+B sobre I2P (roda na VPS). Full node com destination PERSISTENTE (endereço
 * estável do bootstrap entre reinícios): a chave privada da destination é gerada uma vez e
 * cacheada em [--dest-file]. Serve a obra de teste (para o leitor mobile do plano A baixar) e
 * aceita push do publicador anônimo (backbone T1). Imprime `R_DEST`/`R_ID` para o probe do DEV.
 *
 *   I2pServerMain --nick=poc6-r --seed=poc6-r --dest-file=/opt/poc06/r.dest [--publish]
 */
object I2pServerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.associate {
            val a = it.removePrefix("--"); if (a.contains('=')) a.substringBefore('=') to a.substringAfter('=') else a to "true"
        }
        val nick = opts["nick"] ?: "poc6-r"
        val seed = opts["seed"] ?: "poc6-r"
        val destFile = opts["dest-file"]?.let { File(it) }
        val samHost = I2pRig.samHost(); val samPort = I2pRig.samPort()

        // destination persistente: gera uma vez, reusa (endereço estável do bootstrap)
        val destKey = destFile?.takeIf { it.exists() }?.readText()?.trim()
            ?: SamSession.generateDestination(samHost, samPort).first.also { k ->
                destFile?.writeText(k); println("dest gerada e cacheada em ${destFile?.path}")
            }

        // bootstrap opcional (T2): --peer=<destination>:<idHex> — R entra na malha por B
        val peers = opts["peer"]?.let { spec ->
            val idx = spec.lastIndexOf(':')
            listOf(BootstrapAddr(spec.substring(0, idx), 0, spec.substring(idx + 1)))
        } ?: emptyList()

        val tuning = AnnounceTuning(ttlMillis = 5 * 60_000, republishMillis = 15_000)
        val (r, session) = TramaBackend.i2pFullNode(seed, tuning, samHost, samPort, nick, destKey)
        r.serve(MemoryBlockstore())
        r.acceptPushes(TckVectors.contentKeys.idHex)
        r.start(ListenSpec(0), peers)

        if (opts.containsKey("publish")) {
            val prep = TckVectors.prepared()
            r.publish(TckVectors.OBRA, prep.manifestBlock, prep.blocks)
            r.announce(TckVectors.OBRA)
            println("PUBLICADO obra=${TckVectors.OBRA} blocos=${prep.blocks.size}")
        }

        println("R_UP id=${r.idHex}")
        println("R_DEST=${session.myDestination}")
        println("R_ID=${r.idHex}")
        println("(R = B+R co-localizados na VPS — a ressalva de topologia de descoberta do design)")
        Thread.currentThread().join()
    }
}
