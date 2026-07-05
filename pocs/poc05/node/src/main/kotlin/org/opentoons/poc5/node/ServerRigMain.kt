package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 (teste com VPS real) — roda NA VPS: B (bootstrap) + R (replicador, aceita push,
 * dual-homed IP público + onion). Fica vivo. O publicador P roda no DESKTOP e empurra por
 * clearnet (internet real desktop→VPS) e por Tor (onion da VPS). Backend Trama (JVM puro).
 *
 * uso: ServerRigMainKt --public=<ip> --onion=<host>.onion [--b-port=4200] [--r-port=4100]
 */
fun main(args: Array<String>) {
    val opts = NodeRunner.parseArgs(args)
    val publicIp = opts["public"] ?: error("--public=<ip>")
    val onion = opts["onion"] ?: error("--onion=<host>.onion")
    val bPort = opts["b-port"]?.toInt() ?: 4200
    val rPort = opts["r-port"]?.toInt() ?: 4100
    val tuning = AnnounceTuning(ttlMillis = 10 * 60_000, republishMillis = 5_000)

    val b = TramaBackend.fullNode("poc5-bootstrap-B", tuning)
    b.serve(MemoryBlockstore())
    b.start(ListenSpec(bPort, publicHost = publicIp, publicPort = bPort), emptyList())
    println("B UP público=$publicIp:$bPort id=${b.idHex}")

    val r = TramaBackend.fullNode("poc5-replicador-R", tuning)
    r.serve(MemoryBlockstore())
    r.acceptPushes(TckVectors.contentKeys.idHex)
    r.advertise("$onion:$rPort") // dual-homed: onion p/ o publicador anônimo
    r.start(
        ListenSpec(rPort, publicHost = publicIp, publicPort = rPort),
        listOf(BootstrapAddr("127.0.0.1", bPort, b.idHex)),
    )
    r.announce(TckVectors.OBRA) // anuncia (hospedará a obra) → dual-homed no record
    println("R UP público=$publicIp:$rPort id=${r.idHex}")
    println("R_ID=${r.idHex}")
    println("B_ID=${b.idHex}")
    println("R_CLEAR=$publicIp:$rPort")
    println("R_ONION=$onion:$rPort")
    println("(vivo — Ctrl-C para encerrar)")
    Thread.currentThread().join()
}
