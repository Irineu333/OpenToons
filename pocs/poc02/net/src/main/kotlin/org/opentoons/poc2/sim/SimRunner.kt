package org.opentoons.poc2.sim

import kotlin.random.Random

/**
 * E3 — roda as duas variantes de descoberta com n ∈ {10, 100, 1.000, 10.000} + churn
 * e imprime a matriz de métricas do relatório (design D3).
 *
 * Cenário por escala: 20 obras (5 se n=10), 3 réplicas/obra (2 se n=10); bootstrap é o
 * nó 0; churn = 20% dos nós não-publicadores mortos + mesmo número de nós novos entrando
 * frios pelo bootstrap. Janela de tráfego: 60 ticks (10 min simulados) em regime.
 */
object SimScenario {
    const val TRAFFIC_WINDOW_TICKS = 60
    const val CONVERGENCE_CAP_TICKS = 600
    const val CHURN_FRACTION = 0.20
    const val LOOKUP_SAMPLES = 30
    const val CONVERGENCE_PROBES = 20

    fun obrasFor(n: Int, rng: Random): Map<String, List<Int>> {
        val obraCount = if (n >= 100) 20 else 5
        val replicas = if (n >= 100) 3 else 2
        val eligible = (1 until n).toMutableList().also { it.shuffle(rng) }
        return (0 until obraCount).associate { o ->
            "obra-$o" to (0 until replicas).map { r -> eligible[(o * replicas + r) % eligible.size] }
        }
    }
}

private fun avg(xs: List<Int>): Double = xs.sum().toDouble() / xs.size

fun runGossip(n: Int): VariantMetrics {
    val rng = rngFor("gossip", n)
    val obras = SimScenario.obrasFor(n, rng)
    val net = GossipNetwork(n, obras, rng)

    repeat(12) { net.doTick() } // regime: anúncios propagados

    // lookups frio/quente
    val coldRtts = ArrayList<Int>()
    val warmRtts = ArrayList<Int>()
    val client = net.Client()
    repeat(SimScenario.LOOKUP_SAMPLES) {
        val obra = obras.keys.random(rng)
        val cold = net.Client().coldLookup(obra, bootstrapIdx = 0)
        check(cold.success) { "gossip n=$n: lookup frio falhou para $obra" }
        coldRtts.add(cold.rtts)
        if (it == 0) client.coldLookup(obra, 0)
        val warm = client.warmLookup(obra)
        check(warm.success)
        warmRtts.add(warm.rtts)
    }

    // tráfego em regime
    net.nodes.forEach { it?.counters?.reset() }
    repeat(SimScenario.TRAFFIC_WINDOW_TICKS) { net.doTick() }
    val alive = net.aliveIndices()
    val hours = SimScenario.TRAFFIC_WINDOW_TICKS * TICK_SECONDS / 3600.0
    val trafficKb = alive.sumOf {
        val c = net.nodes[it]!!.counters
        c.bytesIn + c.bytesOut
    } / alive.size.toDouble() / hours / 1024.0
    val memKb = alive.sumOf { net.nodes[it]!!.memoryBytes() } / alive.size.toDouble() / 1024.0

    // churn: 20% fora (nunca publicadores/bootstrap) + mesmo número entrando frio
    val publishers = obras.values.flatten().toSet()
    val victims = alive.filter { it != 0 && it !in publishers }.shuffled(rng)
        .take((n * SimScenario.CHURN_FRACTION).toInt())
    victims.forEach { net.kill(it) }
    repeat(victims.size) { net.addNodeBootstrapped() }
    var convergenceTicks = -1
    for (t in 1..SimScenario.CONVERGENCE_CAP_TICKS) {
        net.doTick()
        val probesOk = (1..SimScenario.CONVERGENCE_PROBES).all {
            net.Client().coldLookup(obras.keys.random(rng), 0).success
        }
        if (probesOk && net.membershipConverged()) {
            convergenceTicks = t
            break
        }
    }

    return VariantMetrics(
        variant = "gossip",
        n = n,
        coldLookupRtts = avg(coldRtts),
        warmLookupRtts = avg(warmRtts),
        trafficPerNodePerHourKb = trafficKb,
        memoryPerNodeKb = memKb,
        convergenceAfterChurnSeconds = if (convergenceTicks < 0) -1 else convergenceTicks * TICK_SECONDS,
        clientInboundRequests = client.inboundRequests,
        clientStoredRecords = client.storedRecords,
    )
}

fun runKademlia(n: Int): VariantMetrics {
    val rng = rngFor("kademlia", n)
    val obras = SimScenario.obrasFor(n, rng)
    val net = KademliaNetwork(n, obras, rng)

    repeat(12) { net.doTick() }

    val coldRtts = ArrayList<Int>()
    val client = net.Client()
    repeat(SimScenario.LOOKUP_SAMPLES) {
        val obra = obras.keys.random(rng)
        val cold = net.Client().coldLookup(obra, bootstrapIdx = 0)
        check(cold.success) { "kademlia n=$n: lookup frio falhou para $obra" }
        coldRtts.add(cold.rtts)
    }
    // Kademlia não tem lookup "quente" para cliente puro: a tabela nasce fria a cada
    // abertura do app (ADR-0005) — warm = cold por construção
    val warmRtts = coldRtts

    net.nodes.forEach { it?.counters?.reset() }
    repeat(SimScenario.TRAFFIC_WINDOW_TICKS) { net.doTick() }
    val alive = net.aliveIndices()
    val hours = SimScenario.TRAFFIC_WINDOW_TICKS * TICK_SECONDS / 3600.0
    val trafficKb = alive.sumOf {
        val c = net.nodes[it]!!.counters
        c.bytesIn + c.bytesOut
    } / alive.size.toDouble() / hours / 1024.0
    val memKb = alive.sumOf { net.nodes[it]!!.memoryBytes() } / alive.size.toDouble() / 1024.0

    val publishers = obras.values.flatten().toSet()
    val victims = alive.filter { it != 0 && it !in publishers }.shuffled(rng)
        .take((n * SimScenario.CHURN_FRACTION).toInt())
    victims.forEach { net.kill(it) }
    repeat(victims.size) { net.addNodeAndJoin(bootstrap = 0) }
    var convergenceTicks = -1
    for (t in 1..SimScenario.CONVERGENCE_CAP_TICKS) {
        net.doTick()
        val probesOk = (1..SimScenario.CONVERGENCE_PROBES).all {
            net.Client().coldLookup(obras.keys.random(rng), 0).success
        }
        if (probesOk) {
            convergenceTicks = t
            break
        }
    }

    return VariantMetrics(
        variant = "kademlia",
        n = n,
        coldLookupRtts = avg(coldRtts),
        warmLookupRtts = avg(warmRtts),
        trafficPerNodePerHourKb = trafficKb,
        memoryPerNodeKb = memKb,
        convergenceAfterChurnSeconds = if (convergenceTicks < 0) -1 else convergenceTicks * TICK_SECONDS,
        clientInboundRequests = client.inboundRequests,
        clientStoredRecords = client.storedRecords,
    )
}

fun main() {
    val results = ArrayList<VariantMetrics>()
    for (n in listOf(10, 100, 1_000, 10_000)) {
        for (variant in listOf("gossip", "kademlia")) {
            val start = System.currentTimeMillis()
            val m = if (variant == "gossip") runGossip(n) else runKademlia(n)
            results.add(m)
            System.err.println("[$variant n=$n] concluído em ${(System.currentTimeMillis() - start) / 1000}s")
        }
    }
    println("| Variante | n | RTTs lookup frio | RTTs quente | Tráfego/nó/h (KB) | Memória/nó (KB) | Convergência pós-churn (s) | Cliente: entrada/registros |")
    println("|---|---|---|---|---|---|---|---|")
    results.forEach { m ->
        val conv = if (m.convergenceAfterChurnSeconds < 0) "> cap" else "${m.convergenceAfterChurnSeconds}"
        println(
            "| ${m.variant} | ${"%,d".format(m.n)} | ${percent(m.coldLookupRtts)} | ${percent(m.warmLookupRtts)} " +
                "| ${percent(m.trafficPerNodePerHourKb)} | ${percent(m.memoryPerNodeKb)} | $conv " +
                "| ${m.clientInboundRequests}/${m.clientStoredRecords} |",
        )
    }
}
