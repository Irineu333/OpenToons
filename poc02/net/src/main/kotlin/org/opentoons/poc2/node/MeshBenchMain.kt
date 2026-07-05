package org.opentoons.poc2.node

import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.transport.NetStats

/**
 * Refresh do poc-02 — substitui a SIMULAÇÃO do E3 (gossip×Kademlia idealizado) por uma
 * MALHA REAL: N nós plenos [FullNode] no mesmo processo, mas com sockets TCP, handshake
 * Noise e protocolo de gossip (HELLO/PEX/ANNOUNCE/RESOLVE) 100% reais entre si. Mede o que
 * a sim modelava — lookup frio (RTTs), propagação de anúncio e tráfego/nó — agora com
 * conexões reais. Escala real limitada (≪ 10k da sim); o número extremo continua sendo
 * projeção, mas a mecânica e o custo por rodada passam a ser medidos.
 *
 * uso: MeshBenchMainKt [--sizes=4,8,16,32] [--mesh-ms=1000] [--ttl-ms=8000] [--obras=20] [--window-rounds=8]
 */
object MeshBench {

    private class Mesh(
        val nodes: List<FullNode>,
        val meshMs: Long,
    ) {
        fun converge(rounds: Int) {
            repeat(rounds) { nodes.forEach { runCatching { it.meshNow() } } }
        }
        fun closeAll() = nodes.forEach { runCatching { it.close() } }
    }

    private fun buildMesh(n: Int, meshMs: Long, ttlMs: Long): Mesh {
        val nodes = ArrayList<FullNode>(n)
        // nó 0 primeiro (é o bootstrap conhecido por todos)
        val first = FullNode(
            identity = NodeIdentity.fromSeed("poc2-mesh-0".toByteArray()),
            publicAddress = "127.0.0.1:0",
            listenPort = 0,
            meshIntervalMs = meshMs,
            announceTtlMs = ttlMs,
        )
        nodes.add(first)
        for (i in 1 until n) {
            nodes.add(
                FullNode(
                    identity = NodeIdentity.fromSeed("poc2-mesh-$i".toByteArray()),
                    publicAddress = "127.0.0.1:0",
                    listenPort = 0,
                    bootstrap = listOf(first.self),
                    meshIntervalMs = meshMs,
                    announceTtlMs = ttlMs,
                ),
            )
        }
        return Mesh(nodes, meshMs)
    }

    /** Lookup frio real: cliente efêmero (ADR-0005) dial no bootstrap → PEX+RESOLVE. */
    private fun coldLookup(bootstrap: FullNode.NodeAddress, obra: String): Pair<Int, Long> {
        val client = ClientSession(NodeIdentity.generate())
        val t0 = System.nanoTime()
        val disc = client.coldDiscover(bootstrap, obra)
        val ms = (System.nanoTime() - t0) / 1_000_000
        check(disc.providers.isNotEmpty()) { "lookup frio não achou provider de $obra" }
        return disc.rtts to ms
    }

    fun run(args: Array<String>) {
        val opts = args.filter { it.startsWith("--") }.associate {
            val kv = it.removePrefix("--").split("=", limit = 2); kv[0] to kv.getOrElse(1) { "true" }
        }
        // um N por processo (isola pressão de portas efêmeras/TIME_WAIT entre tamanhos)
        val sizes = (opts["n"] ?: opts["sizes"] ?: "4,8,16,32").split(",").map { it.toInt() }
        val meshMs = opts["mesh-ms"]?.toLong() ?: 1000L
        val ttlMs = opts["ttl-ms"]?.toLong() ?: 8000L
        val obras = opts["obras"]?.toInt() ?: 20
        val windowRounds = opts["window-rounds"]?.toInt() ?: 5
        val convergeRounds = opts["converge-rounds"]?.toInt() ?: 5
        val header = opts["header"] != "false"

        if (header) {
            println("=== MALHA REAL (gossip Noise/TCP) — refresh do E3 do poc-02 ===")
            println("obras=$obras ttl=${ttlMs}ms mesh-round=${meshMs}ms janela=$windowRounds rodadas")
            println("| N | lookup RTTs | lookup ms | anúncio→resolvível ms | tráfego/nó/rodada (KB) | tráfego/nó/h @refresh=${meshMs}ms (KB) |")
            println("|---|---|---|---|---|---|")
        }

        for (n in sizes) {
            val mesh = buildMesh(n, meshMs, ttlMs)
            try {
                // 1) espalha `obras` obras pelos nós (obra j no nó j%n) e converge a malha
                val obraIds = (0 until obras).map { "opentoons/obra-$it" }
                obraIds.forEachIndexed { j, obra ->
                    mesh.nodes[j % n].publishChapter(obra, "$obra/cap-001", seq = 1, pages = TestChapter.PAGES.take(1))
                }
                mesh.converge(rounds = convergeRounds) // deixa membership + anúncios propagarem

                val bootstrap = mesh.nodes[0].self

                // 2) lookup frio real de uma obra publicada num nó != bootstrap
                val targetObra = obraIds.first { obraIds.indexOf(it) % n != 0 }
                val (rtts, lookupMs) = coldLookup(bootstrap, targetObra)

                // 3) propagação de anúncio: publica obra NOVA num nó aleatório e mede
                //    tempo até o bootstrap resolvê-la (join→resolvível)
                val newObra = "opentoons/obra-nova-$n"
                val publisher = mesh.nodes[n / 2]
                val tPub = System.nanoTime()
                publisher.publishChapter(newObra, "$newObra/cap-001", seq = 1, pages = TestChapter.PAGES.take(1))
                var propMs = -1L
                val client = ClientSession(NodeIdentity.generate())
                for (round in 0 until 15) { // bounded: evita dial-storm; propaga em poucas rodadas
                    mesh.nodes.forEach { runCatching { it.meshNow() } }
                    val d = runCatching { client.coldDiscover(bootstrap, newObra) }.getOrNull()
                    if (d != null && d.providers.isNotEmpty()) {
                        propMs = (System.nanoTime() - tPub) / 1_000_000
                        break
                    }
                }

                // 4) tráfego real: mede bytes no fio durante `windowRounds` rodadas de malha
                //    em regime; total/N = tráfego médio por nó. Extrapola /hora ao refresh dado.
                NetStats.reset()
                val tWin = System.nanoTime()
                repeat(windowRounds) {
                    mesh.nodes.forEach { runCatching { it.meshNow() } }
                }
                val winMs = (System.nanoTime() - tWin) / 1_000_000
                val total = NetStats.total()
                val perNodeRoundKb = total.toDouble() / n / windowRounds / 1024.0
                val roundsPerHour = 3_600_000.0 / meshMs
                val perNodeHourKb = perNodeRoundKb * roundsPerHour

                println(
                    "| %d | %d | %d | %s | %.1f | %.0f |".format(
                        n, rtts, lookupMs,
                        if (propMs < 0) ">30000" else propMs.toString(),
                        perNodeRoundKb, perNodeHourKb,
                    ),
                )
            } finally {
                mesh.closeAll()
                Thread.sleep(300)
            }
        }
        println("(tráfego/rodada é medido; /hora é projeção linear ao intervalo de refresh — bytes reais no fio)")
    }
}

fun main(args: Array<String>) = MeshBench.run(args)
