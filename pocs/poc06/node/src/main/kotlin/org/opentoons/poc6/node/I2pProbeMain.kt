package org.opentoons.poc6.node

import org.opentoons.poc6.api.BootstrapAddr
import org.opentoons.poc6.api.ChapterVerifier
import org.opentoons.poc6.api.ContentId
import org.opentoons.poc6.api.ManifestCodec
import org.opentoons.poc6.api.ObraId
import org.opentoons.poc6.api.Provider
import org.opentoons.poc6.api.tck.TckVectors
import org.opentoons.poc6.trama.TramaBackend

/**
 * poc-06 — probe do publicador/leitor (roda no DEV, rede residencial separada da VPS). Disca R
 * por destination I2P (NUNCA por IP — o caminho é P(DEV) ══I2P══ R(VPS), duas redes reais) e
 * cronometra os testes da campanha. Cada operação abre uma sessão SAM transiente (o leitor/
 * publicador só-saída do ADR-0005). Modos:
 *
 *   --mode=push     T1: empurra a obra a R por I2P e cronometra (backbone, publicação)
 *   --mode=speed    T1: baixa a obra N vezes de R, mede throughput mediano SOBRE I2P
 *   --mode=discover T2: conhece só B, descobre R via gossip SOBRE I2P e cronometra
 *   --mode=fetch    T3-análogo (no DEV): descoberta fria + download + verificação
 *
 * Args: --r-dest=<dest> --r-id=<hex> [--b-dest=<dest> --b-id=<hex>] [--n=5] --nick=poc6-probe
 */
object I2pProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.associate {
            val a = it.removePrefix("--"); if (a.contains('=')) a.substringBefore('=') to a.substringAfter('=') else a to "true"
        }
        val rDest = opts["r-dest"] ?: error("--r-dest=<destination>")
        val rId = opts["r-id"] ?: error("--r-id=<idHex>")
        val rProvider = Provider(rId, listOf(rDest))
        val n = opts["n"]?.toInt() ?: 5
        val nick = opts["nick"] ?: "poc6-probe"
        val obra = TckVectors.OBRA

        when (opts["mode"] ?: "fetch") {
            "push" -> {
                val t0 = I2pRig.now()
                val client = TramaBackend.i2pClient(nick = "$nick-push")
                val warm = I2pRig.now() - t0
                println("SESSAO SAM pronta em ${I2pRig.secs(warm)}s (build de túnel do publicador)")
                val prep = TckVectors.prepared()
                val tp = I2pRig.now()
                client.push(rProvider, obra, prep.manifestBlock, prep.blocks)
                val push = I2pRig.now() - tp
                val kib = prep.blocks.sumOf { it.bytes.size } / 1024.0
                println("PUSH_OK ${prep.blocks.sumOf { it.bytes.size }} B em ${I2pRig.secs(push)}s = ${"%.1f".format(kib / (push / 1000.0))} KiB/s")
                client.close()
            }
            "speed" -> {
                val client = TramaBackend.i2pClient(nick = "$nick-speed")
                val ids = ManifestCodec.decode(client.getManifest(rProvider, obra)).manifest.blockCids.map { ContentId(it) }
                val kib = ids.size * 256.0
                val times = ArrayList<Long>()
                repeat(n) { i ->
                    val t0 = I2pRig.now()
                    val blocks = client.getBlocks(rProvider, ids)
                    val dt = I2pRig.now() - t0
                    times.add(dt)
                    println("SPEED[$i] ${blocks.sumOf { it.bytes.size }} B em ${dt} ms = ${"%.1f".format(kib / (dt / 1000.0))} KiB/s")
                }
                val med = times.sorted()[times.size / 2]
                println("SPEED_MEDIAN ${med} ms = ${"%.1f".format(kib / (med / 1000.0))} KiB/s (${n} amostras, SOBRE I2P)")
                client.close()
            }
            "discover" -> {
                val bDest = opts["b-dest"] ?: error("--b-dest=<destination> (T2 conhece só B)")
                val bId = opts["b-id"] ?: error("--b-id=<idHex>")
                val client = TramaBackend.i2pClient(nick = "$nick-disc")
                client.dial(BootstrapAddr(bDest, 0, bId))
                val t0 = I2pRig.now()
                val provider = awaitProvider(client, obra) ?: error("DISCOVER FALHOU: R não descoberto via B")
                println("DISCOVER_OK R descoberto em ${I2pRig.secs(I2pRig.now() - t0)}s via gossip SOBRE I2P (P conhecia só B)")
                println("  provider=${provider.id.take(16)}… addr=${provider.addresses.first().take(24)}…")
                client.close()
            }
            "fetch" -> {
                val bDest = opts["b-dest"] ?: rDest
                val bId = opts["b-id"] ?: rId
                val t0 = I2pRig.now()
                val client = TramaBackend.i2pClient(nick = "$nick-fetch")
                println("SESSAO SAM (leitor frio) pronta em ${I2pRig.secs(I2pRig.now() - t0)}s")
                client.dial(BootstrapAddr(bDest, 0, bId))
                val provider = awaitProvider(client, obra) ?: error("FETCH FALHOU: descoberta")
                val tFirst = I2pRig.now()
                val manifest = client.getManifest(provider, obra)
                val ttfb = I2pRig.now() - tFirst
                val ids = ManifestCodec.decode(manifest).manifest.blockCids.map { ContentId(it) }
                val blocks = client.getBlocks(provider, ids)
                val total = I2pRig.now() - t0
                val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifest, blocks.map { it.bytes })
                val v = result as? ChapterVerifier.Result.Verified ?: error("verificação: $result")
                println("FETCH_OK ${v.chapter.size} B verificados | total(frio)=${I2pRig.secs(total)}s ttfb=${I2pRig.secs(ttfb)}s")
                client.close()
            }
        }
    }

    private fun awaitProvider(client: org.opentoons.poc6.api.P2pBackend, obra: ObraId, timeoutMs: Long = 90_000): Provider? {
        val deadline = I2pRig.now() + timeoutMs
        while (I2pRig.now() < deadline) {
            val ps = runCatching { client.resolve(obra) }.getOrDefault(emptyList())
            if (ps.isNotEmpty()) return ps.first()
            Thread.sleep(1_000)
        }
        return null
    }
}
