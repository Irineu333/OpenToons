package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.Capability
import org.opentoons.poc5.api.ChapterVerifier
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.ManifestCodec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.Provider
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend
import org.opentoons.poc5.trama.TramaBackend

/**
 * poc-05 E1/E2 — a PROVA REAL do modo anônimo sobre um daemon Tor local, NEUTRA quanto ao
 * backend (task 6.5: mesmo driver, zero branch por backend além das fábricas). Circuito
 * 100% Tor (onion v3, 6 hops, sem exit):
 *
 *   publicador P (só saída, SOCKS5h) ──[onion]──▶ replicador R (127.0.0.1:4100)
 *   leitor M (clearnet direto a R) ──────────────▶ baixa e verifica o que R recebeu por push
 *
 * Prova (executado, não declarado): SOCKS5h sem DNS local (onion não resolve localmente);
 * handshake autentica R através do circuito; push cabe no túnel; ANONYMOUS_DIAL presente no
 * publicador e ausente no leitor; chave errada rejeitada pelo túnel.
 *
 * uso: AnonRigMainKt --backend=trama|libp2p --onion=<host>.onion [--port=4100] [--socks=9050]
 */
object AnonRig {

    /** Fábricas do backend selecionado — o ÚNICO ponto onde o backend entra (D1). */
    private class Backend(
        val fullNode: (String, AnnounceTuning) -> FullNode,
        val anonClient: (Int) -> P2pBackend,
        val clearClient: () -> P2pBackend,
        val onionProvider: (String, String, Int) -> Provider,
    )

    private fun backendOf(name: String): Backend = when (name) {
        "trama" -> Backend(
            fullNode = { seed, t -> TramaBackend.fullNode(seed, t) },
            anonClient = { socks -> TramaBackend.client(AnonymityConfig.tor(socksPort = socks)) },
            clearClient = { TramaBackend.client() },
            onionProvider = { id, host, port -> TramaBackend.onionProvider(id, host, port) },
        )
        "libp2p" -> Backend(
            fullNode = { seed, t -> Libp2pBackend.fullNode(seed, t) },
            anonClient = { socks -> Libp2pBackend.client(AnonymityConfig.tor(socksPort = socks)) },
            clearClient = { Libp2pBackend.client() },
            onionProvider = { id, host, port -> Libp2pBackend.onionProvider(id, host, port) },
        )
        else -> error("backend desconhecido: $name (trama|libp2p)")
    }

    fun run(args: Array<String>) {
        val opts = NodeRunner.parseArgs(args)
        val backendName = opts["backend"] ?: error("--backend=trama|libp2p obrigatório")
        val onion = opts["onion"] ?: error("--onion=<host>.onion obrigatório")
        val rPort = opts["port"]?.toInt() ?: 4100
        val socksPort = opts["socks"]?.toInt() ?: 9050
        val b = backendOf(backendName)
        println("=== E1/E2 anônimo — backend=$backendName onion=$onion:$rPort ===")

        // --- R: replicador (escuta clearnet local; alcançável pelo onion) ---
        val r = b.fullNode("poc5-replicador-R", AnnounceTuning())
        r.serve(MemoryBlockstore())
        r.acceptPushes(TckVectors.contentKeys.idHex) // aceita SÓ a editora legítima
        r.start(ListenSpec(rPort), emptyList())
        println("R UP id=${r.idHex.take(16)}… port=${r.boundPort}")

        val rViaOnion = b.onionProvider(r.idHex, onion, rPort)
        val rViaClearnet = r.selfProvider()
        var failures = 0

        // === Cenário A: push AUTÊNTICO pelo onion → reader clearnet verifica ===
        run {
            val pub = b.anonClient(socksPort)
            check(Capability.ANONYMOUS_DIAL in pub.capabilities) { "client anônimo sem ANONYMOUS_DIAL" }
            val prep = TckVectors.prepared()
            val t0 = System.currentTimeMillis()
            pub.push(rViaOnion, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
            val dtPush = System.currentTimeMillis() - t0
            pub.close()
            println("PUSH anônimo (onion, ${prep.blocks.size} blocos ~768 KiB): ${dtPush} ms  [limiar D7 < 60000 ms]")
            if (dtPush >= 60_000) println("  ⚠ excedeu o limiar de push")

            val reader = b.clearClient()
            check(Capability.ANONYMOUS_DIAL !in reader.capabilities) { "leitor clearnet com ANONYMOUS_DIAL?" }
            val manifestBlock = reader.getManifest(rViaClearnet, TckVectors.OBRA)
            val ids = ManifestCodec.decode(manifestBlock).manifest.blockCids.map { ContentId(it) }
            val blocks = reader.getBlocks(rViaClearnet, ids)
            val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifestBlock, blocks.map { it.bytes })
            reader.close()
            if (result is ChapterVerifier.Result.Verified && result.chapter.contentEquals(TckVectors.chapterBytes)) {
                println("✅ A: push anônimo gravado e servido íntegro (${result.chapter.size} bytes verificados)")
            } else {
                println("❌ A: verificação falhou: $result"); failures++
            }
        }

        // === Cenário B: push de CHAVE ERRADA pelo onion → rejeitado antes de gravar ===
        run {
            val obraB = ObraId("opentoons/serie-teste-impostor")
            val pub = b.anonClient(socksPort)
            val forged = TckVectors.preparedWrongKey()
            val rejected = runCatching { pub.push(rViaOnion, obraB, forged.manifestBlock, forged.blocks) }.isFailure
            pub.close()
            val reader = b.clearClient()
            val absent = runCatching { reader.getManifest(rViaClearnet, obraB) }.isFailure
            reader.close()
            if (rejected && absent) {
                println("✅ B: push de chave errada rejeitado pelo túnel, R não gravou")
            } else {
                println("❌ B: rejected=$rejected absent=$absent (deveria ser ambos true)"); failures++
            }
        }

        r.stop()
        println(if (failures == 0) "RESULTADO $backendName-anônimo: TODOS OS CENÁRIOS VERDES" else "RESULTADO $backendName-anônimo: $failures FALHA(S)")
        if (failures != 0) kotlin.system.exitProcess(1)
    }
}

fun main(args: Array<String>) = AnonRig.run(args)
