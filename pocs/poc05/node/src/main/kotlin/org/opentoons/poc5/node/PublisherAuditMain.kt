package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.Capability
import org.opentoons.poc5.api.NodeKeys
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 E4-T (D5 camadas 1/2, SEM sudo) — publicador ANÔNIMO ISOLADO num processo próprio,
 * para o audit de sockets: um `lsof` deste PID durante o push deve mostrar (2) ZERO sockets
 * de escuta não-loopback e (1) conexões SÓ para 127.0.0.1:<socks> (o daemon Tor) — nenhum
 * dial direto, nenhum DNS. Imprime o próprio PID para o driver capturar.
 *
 * Requer R (seed "poc5-replicador-R") escutando no onion. uso:
 *   PublisherAuditMainKt --backend=trama|libp2p --onion=<R>.onion [--port=4100] [--socks=9050]
 */
fun main(args: Array<String>) {
    val opts = NodeRunner.parseArgs(args)
    val backend = opts["backend"] ?: error("--backend")
    val onion = opts["onion"] ?: error("--onion")
    val port = opts["port"]?.toInt() ?: 4100
    val socks = opts["socks"]?.toInt() ?: 9050
    val rIdHex = NodeKeys.fromSeed("poc5-replicador-R").idHex

    val pub = when (backend) {
        "trama" -> TramaBackend.client(AnonymityConfig.tor(socksPort = socks))
        "libp2p" -> Libp2pBackend.client(AnonymityConfig.tor(socksPort = socks))
        else -> error("backend")
    }
    check(Capability.ANONYMOUS_DIAL in pub.capabilities)
    val target = when (backend) {
        "trama" -> TramaBackend.onionProvider(rIdHex, onion, port)
        else -> Libp2pBackend.onionProvider(rIdHex, onion, port)
    }
    val pid = ProcessHandle.current().pid()
    println("PUBLISHER_PID=$pid backend=$backend")
    println("(faça: lsof -nP -p $pid -iTCP  →  só deve haver conexão a 127.0.0.1:$socks, 0 LISTEN)")
    val prep = TckVectors.prepared()
    // repete o push algumas vezes para dar janela ao lsof do driver
    repeat(3) { i ->
        val t0 = System.currentTimeMillis()
        runCatching { pub.push(target, TckVectors.OBRA, prep.manifestBlock, prep.blocks) }
            .onSuccess { println("PUSH[$i] ok em ${System.currentTimeMillis() - t0} ms") }
            .onFailure { println("PUSH[$i] falhou: ${it.message}") }
        Thread.sleep(2000)
    }
    pub.close()
    println("PUBLISHER_DONE")
}
