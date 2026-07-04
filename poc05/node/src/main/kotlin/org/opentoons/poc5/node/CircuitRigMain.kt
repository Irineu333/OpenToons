package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.ChapterVerifier
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.ManifestCodec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.P2pException
import org.opentoons.poc5.api.Provider
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend
import org.opentoons.poc5.trama.TramaBackend
import kotlin.concurrent.thread

/**
 * poc-05 E5 (task 7.3) — ROBUSTEZ DE CIRCUITO. Durante o push anônimo de 768 KiB, um circuito
 * Tor é MORTO (via control port). Prova que a transferência RETOMA sem intervenção manual: o
 * retry de app-level (política do publicador, não do seam) re-disca num circuito novo e
 * completa; a verificação da assinatura ao final confirma a integridade (Q9).
 *
 * uso: CircuitRigMainKt --backend=trama|libp2p --onion-r=<host>.onion [--socks=9050]
 */
object CircuitRig {

    fun run(args: Array<String>) {
        val opts = NodeRunner.parseArgs(args)
        val backend = opts["backend"] ?: error("--backend")
        val onion = opts["onion-r"] ?: error("--onion-r")
        val rPort = opts["r-port"]?.toInt() ?: 4100
        val socks = opts["socks"]?.toInt() ?: 9050
        val tuning = AnnounceTuning()
        println("=== E5 robustez de circuito — backend=$backend ===")

        val fullNode = if (backend == "trama") TramaBackend.fullNode("poc5-replicador-R", tuning)
        else Libp2pBackend.fullNode("poc5-replicador-R", tuning)
        val store = MemoryBlockstore()
        fullNode.serve(store)
        fullNode.acceptPushes(TckVectors.contentKeys.idHex)
        fullNode.start(ListenSpec(rPort), emptyList())
        println("R UP id=${fullNode.idHex.take(12)}…")

        val anon = if (backend == "trama") AnonymityConfig.tor(socksPort = socks) else AnonymityConfig.tor(socksPort = socks)
        val pub = if (backend == "trama") TramaBackend.client(anon) else Libp2pBackend.client(anon)
        val target: Provider = if (backend == "trama") TramaBackend.onionProvider(fullNode.idHex, onion, rPort)
        else Libp2pBackend.onionProvider(fullNode.idHex, onion, rPort)
        val prep = TckVectors.prepared()

        // mata os circuitos cliente em 3 rounds (1,5s / 3s / 4,5s) para pegar o push EM VOO
        // independentemente da velocidade do circuito (quente completa em ~4s)
        val killScript = opts["killer"] ?: error("--killer=<caminho absoluto de kill-circuits.sh>")
        val pushDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val killer = thread(start = false) {
            for (round in 1..3) {
                Thread.sleep(1_500)
                if (pushDone.get()) break
                val p = ProcessBuilder("bash", killScript, "poc5control", "9051")
                    .redirectErrorStream(true).start()
                println("  [killer round $round] ${p.inputStream.bufferedReader().readText().trim()}")
                p.waitFor()
            }
        }

        // push com RETRY de app-level (a "retoma sem intervenção"): até 5 tentativas
        var attempts = 0
        var recovered = false
        killer.start()
        val t0 = System.currentTimeMillis()
        for (attempt in 1..5) {
            attempts = attempt
            try {
                pub.push(target, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
                println("  push tentativa $attempt: OK")
                recovered = true
                break
            } catch (e: P2pException) {
                println("  push tentativa $attempt: FALHOU (${e.message?.take(60)}) → re-tentando em circuito novo")
                Thread.sleep(2_000)
            }
        }
        pushDone.set(true)
        val dt = System.currentTimeMillis() - t0
        pub.close()

        var ok = recovered
        if (recovered) {
            // verificação ao final: R gravou a obra íntegra?
            val reader = if (backend == "trama") TramaBackend.client() else Libp2pBackend.client()
            val rClear = fullNode.selfProvider()
            val result = runCatching {
                val mb = reader.getManifest(rClear, TckVectors.OBRA)
                val ids = ManifestCodec.decode(mb).manifest.blockCids.map { ContentId(it) }
                val blocks = reader.getBlocks(rClear, ids)
                ChapterVerifier(TckVectors.contentKeys.idHex).verify(mb, blocks.map { it.bytes })
            }.getOrNull()
            reader.close()
            ok = result is ChapterVerifier.Result.Verified
            println(if (ok) "  verificação final: ✅ capítulo íntegro" else "  verificação final: ❌ $result")
        }

        fullNode.stop()
        println()
        if (ok && attempts > 1) {
            println("✅ E5-robustez: circuito MORTO no meio do push, retomou em $attempts tentativas (${dt} ms) SEM intervenção, capítulo verificado")
        } else if (ok && attempts == 1) {
            println("⚠ E5-robustez: push completou em 1 tentativa (circuito morto tarde demais ou já reconstruído) — capítulo verificado, mas a resiliência não foi exercida")
        } else {
            println("❌ E5-robustez: não retomou após a morte do circuito"); kotlin.system.exitProcess(1)
        }
    }
}

fun main(args: Array<String>) = CircuitRig.run(args)
