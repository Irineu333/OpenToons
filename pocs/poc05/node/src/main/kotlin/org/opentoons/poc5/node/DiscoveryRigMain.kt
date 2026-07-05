package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.Capability
import org.opentoons.poc5.api.ChapterVerifier
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.ManifestCodec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.Provider
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 E3 (tasks 5.1/5.2) — DESCOBERTA do replicador ATRAVÉS DO TOR (cenário C2). O
 * publicador anônimo conhece SÓ o bootstrap B (onion); descobre R — nunca informado — por
 * dentro do túnel, e empurra. Prova que a descoberta também cabe no circuito.
 *
 *   B (bootstrap, onion :4200) ◀── R (replicador, onion :4100) anuncia a obra
 *          ▲ resolve(obra) por dentro do túnel
 *   P (anônimo) conhece só B  ──[Tor]──▶ descobre R ──[Tor]──▶ push
 *
 * Mede a latência de lookup frio contra o limiar refixado (< 10 s, D7/Q2) e a divergência
 * entre PEX/RESOLVE (Trama, 1 circuito) e walk de Kademlia (libp2p, multi-circuito).
 *
 * uso: DiscoveryRigMainKt --backend=trama|libp2p --onion-r=<R>.onion --onion-b=<B>.onion
 */
object DiscoveryRig {

    private class Backend(
        val fullNode: (String, AnnounceTuning) -> FullNode,
        val anonClient: (Int) -> P2pBackend,
        val clearClient: () -> P2pBackend,
    )

    private fun backendOf(name: String): Backend = when (name) {
        "trama" -> Backend(
            { s, t -> TramaBackend.fullNode(s, t) },
            { socks -> TramaBackend.client(AnonymityConfig.tor(socksPort = socks)) },
            { TramaBackend.client() },
        )
        "libp2p" -> Backend(
            { s, t -> Libp2pBackend.fullNode(s, t) },
            { socks -> Libp2pBackend.client(AnonymityConfig.tor(socksPort = socks)) },
            { Libp2pBackend.client() },
        )
        else -> error("backend desconhecido: $name")
    }

    fun run(args: Array<String>) {
        val opts = NodeRunner.parseArgs(args)
        val backendName = opts["backend"] ?: error("--backend=trama|libp2p")
        val onionR = opts["onion-r"] ?: error("--onion-r=<host>.onion")
        val onionB = opts["onion-b"] ?: error("--onion-b=<host>.onion")
        val bPort = opts["b-port"]?.toInt() ?: 4200
        val rPort = opts["r-port"]?.toInt() ?: 4100
        val socksPort = opts["socks"]?.toInt() ?: 9050
        val b = backendOf(backendName)
        // republish curto p/ B aprender R rápido (mecanismo real, TTL curto — como o TCK)
        val tuning = AnnounceTuning(ttlMillis = 60_000, republishMillis = 2_000)
        println("=== E3 descoberta via Tor — backend=$backendName ===")
        println("    B onion=$onionB:$bPort   R onion=$onionR:$rPort")

        // --- B: bootstrap (clearnet local; alcançável por P via onion-B) ---
        val bootstrap = b.fullNode("poc5-bootstrap-B", tuning)
        bootstrap.serve(MemoryBlockstore())
        bootstrap.start(ListenSpec(bPort), emptyList())
        println("B UP id=${bootstrap.idHex.take(12)}… port=${bootstrap.boundPort}")

        // --- R: replicador que ANUNCIA seu endereço ONION e o obra que hospedará ---
        val r = b.fullNode("poc5-replicador-R", tuning)
        r.serve(MemoryBlockstore())
        r.acceptPushes(TckVectors.contentKeys.idHex)
        // publicHost=onionR → R se anuncia alcançável pelo onion (o que P descobrirá)
        r.start(
            ListenSpec(rPort, publicHost = onionR, publicPort = rPort),
            listOf(BootstrapAddr("127.0.0.1", bPort, bootstrap.idHex)),
        )
        r.announce(TckVectors.OBRA) // declara que hospedará a obra (ainda sem tê-la)
        println("R UP id=${r.idHex.take(12)}… anuncia obra por onion=$onionR:$rPort")

        // --- P: publicador ANÔNIMO conhece SÓ B ---
        val pub = b.anonClient(socksPort)
        check(Capability.ANONYMOUS_DIAL in pub.capabilities)
        pub.dial(BootstrapAddr(onionB, bPort, bootstrap.idHex))
        println("P conectou em B (onion) — conhece SÓ B")

        // descoberta de R por dentro do túnel (mede lookup frio contra < 10 s, Q2)
        var discovered: Provider? = null
        val t0 = System.currentTimeMillis()
        val deadline = t0 + 30_000
        while (System.currentTimeMillis() < deadline) {
            val providers = runCatching { pub.resolve(TckVectors.OBRA) }.getOrDefault(emptyList())
            val hit = providers.firstOrNull { it.addresses.any { a -> a.contains(onionR) } }
            if (hit != null) { discovered = hit; break }
            Thread.sleep(500)
        }
        val lookupMs = System.currentTimeMillis() - t0
        var failures = 0
        if (discovered == null) {
            println("❌ E3: P não descobriu R via B em ${lookupMs} ms"); pub.close(); r.stop(); bootstrap.stop()
            kotlin.system.exitProcess(1)
        }
        val within = if (lookupMs < 10_000) "✅ < 10 s" else "⚠ excedeu 10 s (dado honesto)"
        println("✅ E3: R DESCOBERTO via B por dentro do túnel em ${lookupMs} ms  [$within]")
        println("    R nunca foi informado a P; endereço veio do resolve: ${discovered.addresses}")

        // fecha o ciclo: push ao R descoberto → reader clearnet verifica
        val prep = TckVectors.prepared()
        pub.push(discovered, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
        pub.close()
        val reader = b.clearClient()
        val rClear = r.selfProvider()
        val manifestBlock = reader.getManifest(rClear, TckVectors.OBRA)
        val ids = ManifestCodec.decode(manifestBlock).manifest.blockCids.map { ContentId(it) }
        val blocks = reader.getBlocks(rClear, ids)
        val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifestBlock, blocks.map { it.bytes })
        reader.close()
        if (result is ChapterVerifier.Result.Verified) {
            println("✅ E3 ciclo completo: push ao R descoberto gravado e verificado (${result.chapter.size} bytes)")
        } else {
            println("❌ E3: verificação pós-descoberta falhou: $result"); failures++
        }

        r.stop(); bootstrap.stop()
        println(if (failures == 0) "RESULTADO $backendName-descoberta: VERDE (lookup ${lookupMs} ms)" else "RESULTADO $backendName-descoberta: FALHA")
        if (failures != 0) kotlin.system.exitProcess(1)
    }
}

fun main(args: Array<String>) = DiscoveryRig.run(args)
