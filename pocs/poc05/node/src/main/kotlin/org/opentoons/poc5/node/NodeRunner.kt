package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.Blockstore
import org.opentoons.poc5.api.ChapterVerifier
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.ManifestCodec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.api.tck.TamperingBlockstore

/**
 * PoC poc-04 — drivers de host escritos 100% CONTRA O SEAM (`:api`): nenhum conceito nem
 * branch de backend aqui. A escolha do backend vive nos mains (composition roots:
 * TramaMain, Libp2pMain, DualStackMain) — o equivalente de processo à build variant do
 * app (D1). Formatos de argumento:
 *
 *   node   --listen=4100 [--public=IP:porta] [--peer=host:porta:pubkeyhex,...]
 *          [--seed=nome] [--publish] [--tamper] [--ttl=ms] [--republish=ms]
 *   client --bootstrap=host:porta:pubkeyhex --mode=resolve|watch|fetch
 *          [--publisher=pubkeyhex] [--obra=id]
 */
object NodeRunner {

    fun parseArgs(args: Array<String>): Map<String, String> = args.associate {
        val a = it.removePrefix("--")
        if (a.contains('=')) a.substringBefore('=') to a.substringAfter('=')
        else a to "true"
    }

    fun parseBootstrap(spec: String): BootstrapAddr {
        val (host, port, key) = spec.split(":", limit = 3)
        return BootstrapAddr(host, port.toInt(), key)
    }

    private fun tuning(opts: Map<String, String>) = AnnounceTuning(
        ttlMillis = opts["ttl"]?.toLong() ?: 5 * 60_000,
        republishMillis = opts["republish"]?.toLong() ?: 15_000,
    )

    /** Sobe um full node e bloqueia. [factory] é o único ponto onde o backend entra. */
    fun runNode(factory: (String, AnnounceTuning) -> FullNode, args: Array<String>) {
        val opts = parseArgs(args)
        val seed = opts["seed"] ?: "poc5-node"
        val node = factory(seed, tuning(opts))

        val store: Blockstore = MemoryBlockstore().let {
            if (opts.containsKey("tamper")) TamperingBlockstore(it) else it
        }
        node.serve(store)

        val publicHost = opts["public"]?.substringBefore(":")
        val publicPort = opts["public"]?.substringAfter(":")?.toIntOrNull()
        node.start(
            ListenSpec(opts["listen"]?.toInt() ?: 0, publicHost, publicPort),
            opts["peer"]?.split(",")?.map { parseBootstrap(it) } ?: emptyList(),
        )

        if (opts.containsKey("publish")) {
            val prep = if (opts.containsKey("wrongkey")) TckVectors.preparedWrongKey() else TckVectors.prepared()
            node.publish(TckVectors.OBRA, prep.manifestBlock, prep.blocks)
            node.announce(TckVectors.OBRA)
            println("PUBLICADO obra=${TckVectors.OBRA} blocos=${prep.blocks.size}")
        }

        println("NODE UP id=${node.idHex} port=${node.boundPort} bootstrap=${opts["peer"] ?: "-"}")
        println("BOOTSTRAP_ARG=127.0.0.1:${node.boundPort}:${node.idHex}")
        Thread.currentThread().join()
    }

    /** Client de host para asserções do S1/S3: resolve/watch/fetch pelo seam. */
    fun runClient(factory: () -> P2pBackend, args: Array<String>) {
        val opts = parseArgs(args)
        val bootstrap = parseBootstrap(opts["bootstrap"] ?: error("--bootstrap=host:porta:pubkey"))
        val obra = ObraId(opts["obra"] ?: TckVectors.OBRA.value)

        factory().use { client ->
            client.dial(bootstrap)
            when (opts["mode"] ?: "resolve") {
                "resolve" -> {
                    val t0 = System.currentTimeMillis()
                    val providers = client.resolve(obra)
                    println("RESOLVE ${providers.size} provider(s) em ${System.currentTimeMillis() - t0} ms")
                    providers.forEach { println("PROVIDER id=${it.id} addrs=${it.addresses}") }
                }
                "watch" -> while (true) {
                    val providers = runCatching { client.resolve(obra) }.getOrDefault(emptyList())
                    println("${System.currentTimeMillis()} WATCH providers=${providers.size} ${providers.map { it.id.take(12) }}")
                    Thread.sleep(1_000)
                }
                "fetch" -> {
                    val publisherKey = opts["publisher"] ?: TckVectors.contentKeys.idHex
                    val t0 = System.currentTimeMillis()
                    val providers = client.resolve(obra)
                    check(providers.isNotEmpty()) { "nenhum provider para $obra" }
                    val provider = providers.first()
                    println("DESCOBERTO provider=${provider.id} em ${System.currentTimeMillis() - t0} ms")
                    val manifest = client.getManifest(provider, obra)
                    val ids = ManifestCodec.decode(manifest).manifest.blockCids.map { ContentId(it) }
                    val blocks = client.getBlocks(provider, ids)
                    val result = ChapterVerifier(publisherKey).verify(manifest, blocks.map { it.bytes })
                    println("FETCH ${System.currentTimeMillis() - t0} ms → $result".take(200))
                    when (result) {
                        is ChapterVerifier.Result.Verified -> println("VERIFICADO ${result.chapter.size} bytes")
                        else -> println("REJEITADO $result")
                    }
                }
                else -> error("mode desconhecido")
            }
        }
    }
}
