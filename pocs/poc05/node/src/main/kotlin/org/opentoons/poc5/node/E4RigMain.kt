package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.Capability
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.Provider
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 E4 (tasks 6.1-6.3) — orquestrador da MATRIZ E2E real. Sobe a topologia de servidores
 * AQUI (192.168.1.13, público em 177.203.17.5 via port-forward), recebe o push do publicador
 * ANÔNIMO por dentro do Tor, e fica VIVO para o leitor M (Moto g30, dados móveis, rede
 * separada) baixar de R pela CLEARNET — o mesmo app leitor, sem Tor no mobile (non-goal).
 *
 *   P (anônimo, Tor, aqui) ──[onion]──▶ R (público 177.203.17.5:4100) ◀──[clearnet]── M (device)
 *                                        B (público 177.203.17.5:4200) ◀──[clearnet]── M descobre R
 *
 * uso: E4RigMainKt --backend=trama|libp2p --public=177.203.17.5 --onion-r=<host>.onion
 */
object E4Rig {

    private class Backend(
        val fullNode: (String, AnnounceTuning) -> org.opentoons.poc5.api.FullNode,
        val anonClient: (Int) -> org.opentoons.poc5.api.P2pBackend,
        val onionProvider: (String, String, Int) -> Provider,
    )

    private fun backendOf(name: String) = when (name) {
        "trama" -> Backend(
            { s, t -> TramaBackend.fullNode(s, t) },
            { socks -> TramaBackend.client(AnonymityConfig.tor(socksPort = socks)) },
            { id, host, port -> TramaBackend.onionProvider(id, host, port) },
        )
        "libp2p" -> Backend(
            { s, t -> Libp2pBackend.fullNode(s, t) },
            { socks -> Libp2pBackend.client(AnonymityConfig.tor(socksPort = socks)) },
            { id, host, port -> Libp2pBackend.onionProvider(id, host, port) },
        )
        else -> error("backend desconhecido: $name")
    }

    fun run(args: Array<String>) {
        val opts = NodeRunner.parseArgs(args)
        val backendName = opts["backend"] ?: error("--backend=trama|libp2p")
        val publicIp = opts["public"] ?: error("--public=<ip público>")
        val onionR = opts["onion-r"] ?: error("--onion-r=<host>.onion")
        val bPort = opts["b-port"]?.toInt() ?: 4200
        val rPort = opts["r-port"]?.toInt() ?: 4100
        val socksPort = opts["socks"]?.toInt() ?: 9050
        val b = backendOf(backendName)
        val tuning = AnnounceTuning(ttlMillis = 5 * 60_000, republishMillis = 5_000)
        println("=== E4 matriz — backend=$backendName público=$publicIp ===")

        // --- B: bootstrap PÚBLICO (o único endereço que o device conhece) ---
        val bootstrap = b.fullNode("poc5-bootstrap-B", tuning)
        bootstrap.serve(MemoryBlockstore())
        bootstrap.start(ListenSpec(bPort, publicHost = publicIp, publicPort = bPort), emptyList())
        println("B UP público=$publicIp:$bPort id=${bootstrap.idHex.take(12)}…")

        // --- R: replicador PÚBLICO (dual-homed: IP público p/ leitores + onion p/ P) ---
        val scenario = opts["scenario"] ?: "c1"
        val r = b.fullNode("poc5-replicador-R", tuning)
        // --tamper: R grava o push íntegro mas SERVE blocos corrompidos (adulteração neutra
        // do TCK) — o device deve REJEITAR na verificação (BlockHashMismatch).
        val store = if (opts.containsKey("tamper"))
            org.opentoons.poc5.api.tck.TamperingBlockstore(MemoryBlockstore()) else MemoryBlockstore()
        r.serve(store)
        r.acceptPushes(TckVectors.contentKeys.idHex)
        // DUAL-HOMED: no c2, R anuncia TAMBÉM o onion. ANTES do start (o libp2p injeta o onion
        // no ServerConfig na subida; a Trama computa o provider a cada anúncio — os dois OK).
        if (scenario == "c2") r.advertise("$onionR:$rPort")
        r.start(
            ListenSpec(rPort, publicHost = publicIp, publicPort = rPort),
            listOf(BootstrapAddr("127.0.0.1", bPort, bootstrap.idHex)),
        )
        println("R UP público=$publicIp:$rPort id=${r.idHex.take(12)}…")

        // --- P: publicador ANÔNIMO. C1: empurra ao onion CONHECIDO de R. C2: DESCOBRE R via
        // B (onion) por dentro do túnel — P conhece SÓ B — e empurra ao R descoberto. ---
        val pub = b.anonClient(socksPort)
        check(Capability.ANONYMOUS_DIAL in pub.capabilities)
        val prep = TckVectors.prepared()
        val t0 = System.currentTimeMillis()
        val rTarget: Provider = if (scenario == "c2") {
            val onionB = opts["onion-b"] ?: error("c2 exige --onion-b")
            // R já anunciou dual-homed (onion + IP público) no setup. P descobre ambos e casa
            // o onion; o device casa o IP público. Via correta do C2 (push via exit à 4100 é
            // bloqueado pela exit policy — achado D3).
            r.announce(TckVectors.OBRA)
            Thread.sleep(4_000)
            pub.dial(BootstrapAddr(onionB, bPort, bootstrap.idHex))
            var found: Provider? = null
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline) {
                found = runCatching { pub.resolve(TckVectors.OBRA) }.getOrDefault(emptyList()).firstOrNull()
                if (found != null) break
                Thread.sleep(500)
            }
            val disc = checkNotNull(found) { "P não descobriu R via B" }
            println("C2: R DESCOBERTO via B pelo túnel em ${System.currentTimeMillis() - t0} ms (P conhecia só B; R=${disc.addresses})")
            // R é dual-homed: anuncia o IP público (para o device). P empurra a esse endereço
            // por dentro do Tor — caminho EXIT (D3). Se a exit policy bloquear a porta, é dado.
            disc
        } else {
            b.onionProvider(r.idHex, onionR, rPort) // C1: onion conhecido
        }
        pub.push(rTarget, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
        pub.close()
        println("P (anônimo, $scenario) empurrou a obra a R pelo Tor em ${System.currentTimeMillis() - t0} ms")

        // R anuncia a obra que agora hospeda → B aprende R@público; o device descobre por B
        r.announce(TckVectors.OBRA)
        Thread.sleep(6_000) // deixa o anúncio propagar a B

        println()
        println("################## TOPOLOGIA PRONTA — o device pode baixar ##################")
        println("DEVICE_BOOTSTRAP=$publicIp:$bPort:${bootstrap.idHex}")
        println("PUBLISHER=${TckVectors.contentKeys.idHex}")
        println("OBRA=${TckVectors.OBRA.value}")
        println("(processo VIVO — Ctrl-C para encerrar; R/B servindo em $publicIp)")
        Thread.currentThread().join()
    }
}

fun main(args: Array<String>) = E4Rig.run(args)
