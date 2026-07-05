package org.opentoons.poc2.sim

import org.opentoons.poc2.core.sha256
import kotlin.random.Random

/**
 * E3b — Kademlia enxuto próprio, incorporando as lições dos 3 bugs do nabu (poc-01 E5):
 *  1. o ADD_PROVIDER é síncrono e confirmado — não há race de "provide" descartado;
 *  2. requisições de nós plenos ENTRAM na routing table do receptor (o no-op do nabu
 *     quebrava a formação de malha); requisições de CLIENTES não entram (ADR-0005) —
 *     o que no nabu era acidente aqui é regra explícita;
 *  3. o alvo do lookup usa o MESMO espaço de chaves dos nós (sha256 dos dois lados) —
 *     o nabu ordenava o walk com hash inconsistente.
 *
 * Parâmetros comparáveis ao gossip: mesmo TTL de provider e mesmo período de republish.
 */
class KademliaNetwork(
    initialN: Int,
    val obras: Map<String, List<Int>>,
    private val rng: Random,
) {
    companion object {
        const val K = 20
        const val ALPHA = 3
        const val ID_BITS = 256
        const val PROVIDER_TTL_TICKS = 30    // 5 min — igual ao gossip
        const val REPUBLISH_EVERY_TICKS = 6  // 1 min — igual ao announce do gossip
        const val REFRESH_EVERY_TICKS = 60   // refresh de bucket a cada 10 min
    }

    val ids = ArrayList<ByteArray>()
    val nodes = ArrayList<KadNode?>()
    var tick = 0
        private set

    fun keyFor(obra: String): ByteArray = sha256(obra.toByteArray())

    private fun xorDistanceCompare(a: ByteArray, b: ByteArray, target: ByteArray): Int {
        for (i in target.indices) {
            val da = (a[i].toInt() xor target[i].toInt()) and 0xff
            val db = (b[i].toInt() xor target[i].toInt()) and 0xff
            if (da != db) return da - db
        }
        return 0
    }

    private fun bucketIndex(self: ByteArray, other: ByteArray): Int {
        for (i in self.indices) {
            val x = (self[i].toInt() xor other[i].toInt()) and 0xff
            // bit divergente mais significativo → bucket 255; menos significativo → 0
            if (x != 0) return 279 - i * 8 - Integer.numberOfLeadingZeros(x)
        }
        return -1 // mesmo id
    }

    inner class KadNode(val idx: Int) {
        val id: ByteArray get() = ids[idx]
        val buckets = Array(ID_BITS) { ArrayList<Int>(K) }
        val providers = HashMap<String, HashMap<Int, Int>>() // obra → provider → expira no tick
        val counters = Counters()

        /** Lição nº 2 do poc-01: só nós PLENOS entram na tabela; cliente nunca. */
        fun observe(peer: Int) {
            if (peer == idx) return
            val b = bucketIndex(id, ids[peer])
            if (b < 0) return
            val bucket = buckets[b]
            if (peer in bucket) return
            if (bucket.size < K) {
                bucket.add(peer)
            } else {
                // bucket cheio: expulsa um morto se houver; senão descarta o novato (LRU simplificado)
                val deadSlot = bucket.indexOfFirst { nodes[it] == null }
                if (deadSlot >= 0) bucket[deadSlot] = peer
            }
        }

        fun evict(peer: Int) {
            val b = bucketIndex(id, ids[peer])
            if (b >= 0) buckets[b].remove(peer)
        }

        fun closest(target: ByteArray, count: Int): List<Int> =
            buckets.asSequence().flatten()
                .sortedWith { a, b -> xorDistanceCompare(ids[a], ids[b], target) }
                .take(count).toList()

        fun tableSize(): Int = buckets.sumOf { it.size }

        fun memoryBytes(): Long =
            tableSize().toLong() * Wire.MEMBER_RECORD +
                providers.values.sumOf { it.size }.toLong() * Wire.PROVIDER_RECORD
    }

    init {
        repeat(initialN) { addNode() }
        // joins iniciais sequenciais (lookup do próprio id via bootstrap)
        nodes.indices.drop(1).forEach { join(it, bootstrap = 0) }
        obras.forEach { (obra, pubs) -> pubs.forEach { publish(it, obra) } }
    }

    private fun node(i: Int): KadNode? = nodes.getOrNull(i)
    fun aliveIndices(): List<Int> = nodes.indices.filter { nodes[it] != null }

    fun addNode(): Int {
        val idx = nodes.size
        ids.add(sha256("poc2-kad-node-$idx".toByteArray()))
        nodes.add(KadNode(idx))
        return idx
    }

    fun addNodeAndJoin(bootstrap: Int): Int {
        val idx = addNode()
        join(idx, bootstrap)
        return idx
    }

    fun kill(idx: Int) {
        require(obras.values.none { idx in it }) { "cenário mantém publicadores vivos" }
        nodes[idx] = null
    }

    // ---- RPCs (custos de wire contados; morto = timeout) ----

    private fun findNodeRpc(callerCounters: Counters, callerIdx: Int?, to: Int, target: ByteArray): List<Int>? {
        callerCounters.bytesOut += Wire.HEADER + 32
        val server = node(to) ?: return null // timeout
        server.counters.bytesIn += Wire.HEADER + 32
        server.counters.requestsServed++
        callerIdx?.let { server.observe(it) } // lição nº 2: pleno entra, cliente não
        val result = server.closest(target, K)
        val bytes = Wire.HEADER + result.size.toLong() * Wire.MEMBER_RECORD
        server.counters.bytesOut += bytes
        callerCounters.bytesIn += bytes
        return result
    }

    private fun getProvidersRpc(
        callerCounters: Counters,
        callerIdx: Int?,
        to: Int,
        obra: String,
    ): Pair<List<Int>, List<Int>>? {
        callerCounters.bytesOut += Wire.HEADER + 32
        val server = node(to) ?: return null
        server.counters.bytesIn += Wire.HEADER + 32
        server.counters.requestsServed++
        callerIdx?.let { server.observe(it) }
        val provs = server.providers[obra]?.filterValues { it >= tick }?.keys.orEmpty().toList()
        val closer = server.closest(keyFor(obra), K)
        val bytes = Wire.HEADER + provs.size.toLong() * Wire.PROVIDER_RECORD +
            closer.size.toLong() * Wire.MEMBER_RECORD
        server.counters.bytesOut += bytes
        callerCounters.bytesIn += bytes
        return provs to closer
    }

    private fun addProviderRpc(publisher: KadNode, to: Int, obra: String): Boolean {
        publisher.counters.bytesOut += Wire.HEADER + Wire.PROVIDER_RECORD
        val server = node(to) ?: return false
        server.counters.bytesIn += Wire.HEADER + Wire.PROVIDER_RECORD
        server.counters.requestsServed++
        server.observe(publisher.idx)
        server.providers.getOrPut(obra) { HashMap() }[publisher.idx] = tick + PROVIDER_TTL_TICKS
        // lição nº 1: confirmação explícita — sem race, sem descarte silencioso
        server.counters.bytesOut += Wire.HEADER
        publisher.counters.bytesIn += Wire.HEADER
        return true
    }

    /**
     * Lookup iterativo (walk): rodadas de ALPHA consultas em paralelo = 1 RTT por rodada.
     * @return (providers encontrados, nós mais próximos, rodadas/RTTs)
     */
    private fun iterativeLookup(
        callerCounters: Counters,
        callerIdx: Int?,
        seed: List<Int>,
        target: ByteArray,
        obra: String?, // null = FIND_NODE puro
        evictOnTimeout: KadNode? = null,
    ): Triple<List<Int>, List<Int>, Int> {
        val shortlist = sortedSetOf(
            Comparator<Int> { a, b ->
                val c = xorDistanceCompare(ids[a], ids[b], target)
                if (c != 0) c else a - b
            },
        )
        shortlist.addAll(seed)
        val queried = HashSet<Int>()
        var rounds = 0
        val foundProviders = LinkedHashSet<Int>()

        while (rounds < 32) {
            val batch = shortlist.filter { it !in queried }.take(ALPHA)
            if (batch.isEmpty()) break
            rounds++
            var progressed = false
            for (peer in batch) {
                queried.add(peer)
                val response = if (obra == null) {
                    findNodeRpc(callerCounters, callerIdx, peer, target)?.let { emptyList<Int>() to it }
                } else {
                    getProvidersRpc(callerCounters, callerIdx, peer, obra)
                }
                if (response == null) {
                    evictOnTimeout?.evict(peer)
                    continue
                }
                val (provs, closer) = response
                foundProviders.addAll(provs)
                closer.forEach { shortlist.add(it) } // liveness só se descobre consultando
                progressed = true
            }
            if (obra != null && foundProviders.isNotEmpty()) break
            // condição de parada: os K mais próximos já consultados
            val topK = shortlist.take(K)
            if (!progressed || topK.all { it in queried }) break
        }
        return Triple(foundProviders.toList(), shortlist.take(K), rounds)
    }

    fun join(idx: Int, bootstrap: Int) {
        val n = node(idx) ?: return
        n.observe(bootstrap)
        node(bootstrap)?.observe(idx)
        iterativeLookup(n.counters, idx, listOf(bootstrap), n.id, obra = null, evictOnTimeout = n)
            .second.forEach { n.observe(it) }
    }

    fun publish(publisher: Int, obra: String) {
        val pub = node(publisher) ?: return
        val key = keyFor(obra)
        val seed = pub.closest(key, ALPHA).ifEmpty { aliveIndices().filter { it != publisher }.take(1) }
        val (_, closestK, _) = iterativeLookup(pub.counters, publisher, seed, key, obra = null, evictOnTimeout = pub)
        closestK.take(K).forEach { addProviderRpc(pub, it, obra) } // 1 rodada paralela
        // guarda local também (o publicador é provider de si mesmo)
        pub.providers.getOrPut(obra) { HashMap() }[publisher] = tick + PROVIDER_TTL_TICKS
    }

    fun doTick() {
        tick++
        nodes.forEach { n ->
            n?.providers?.values?.forEach { m -> m.entries.removeIf { it.value < tick } }
            n?.providers?.entries?.removeIf { it.value.isEmpty() }
        }
        if (tick % REPUBLISH_EVERY_TICKS == 0) {
            obras.forEach { (obra, pubs) -> pubs.forEach { publish(it, obra) } }
        }
        if (tick % REFRESH_EVERY_TICKS == 0) {
            aliveIndices().forEach { i ->
                val n = node(i)!!
                val randomTarget = sha256("refresh-$tick-$i".toByteArray())
                iterativeLookup(n.counters, i, n.closest(randomTarget, ALPHA), randomTarget, null, n)
                    .second.forEach { n.observe(it) }
            }
        }
    }

    // ---- lado do cliente (ADR-0005: tabela fria a cada abertura do app) ----

    inner class Client {
        val counters = Counters()
        var inboundRequests = 0L // deve permanecer 0 — clientes nunca são observados
        var storedRecords = 0L   // deve permanecer 0 — nada persiste entre lookups

        /** Descoberta fria: só bootstrap + obraId; walk iterativo com tabela vazia. */
        fun coldLookup(obra: String, bootstrapIdx: Int): LookupResult {
            val key = keyFor(obra)
            // RTT 1: FIND_NODE(key) no bootstrap para semear o walk
            val seed = findNodeRpc(counters, null, bootstrapIdx, key)
                ?: return LookupResult(1, emptyList(), false)
            val (provs, _, rounds) = iterativeLookup(counters, null, seed.ifEmpty { listOf(bootstrapIdx) }, key, obra)
            val alive = provs.filter { node(it) != null }
            return LookupResult(1 + rounds, alive, alive.isNotEmpty())
        }
    }
}
