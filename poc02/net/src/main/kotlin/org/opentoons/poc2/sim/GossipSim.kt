package org.opentoons.poc2.sim

import java.util.BitSet
import kotlin.random.Random

/**
 * E3a — membership completo + gossip anti-entropia com digests + PEX.
 *
 * Modelo de protocolo (parâmetros a priori, iguais aos do Kademlia onde comparável):
 *  - cada nó pleno inicia [FANOUT] troca(s) de anti-entropia por tick (tick = 10 s);
 *  - troca: digests ([Wire.DIGEST] cada lado); se diferentes, listas compactas
 *    ([Wire.DIGEST_ENTRY]/entrada) + apenas os registros faltantes;
 *  - providers anunciados por OBRA (decisão da questão Q3 — ver relatório) com expiry
 *    de [PROVIDER_TTL_TICKS] e re-anúncio a cada [ANNOUNCE_EVERY_TICKS];
 *  - membro morto some das visões [FAIL_TTL_TICKS] após a morte (heartbeat piggyback);
 *  - cliente NUNCA entra no membership: pede uma amostra ao bootstrap (PEX) e consulta
 *    1 nó pleno; não armazena, não roteia, não aceita entrada (ADR-0005).
 */
class GossipNetwork(
    initialN: Int,
    /** obraId → índices dos publicadores (mantidos vivos no churn para o cenário ser bem definido). */
    val obras: Map<String, List<Int>>,
    private val rng: Random,
) {
    companion object {
        const val FANOUT = 1
        const val PROVIDER_TTL_TICKS = 30    // 5 min
        const val ANNOUNCE_EVERY_TICKS = 6   // 1 min
        const val FAIL_TTL_TICKS = 3         // 30 s
        const val ANNOUNCE_FANOUT = 3
        const val BOOTSTRAP_SAMPLE = 16
    }

    private val capacity = initialN * 2 + 8
    val nodes = ArrayList<GossipNode?>(capacity)
    private val deadAtTick = HashMap<Int, Int>()
    var tick = 0
        private set

    inner class GossipNode(val idx: Int) {
        val members = BitSet(capacity)
        val providers = HashMap<String, HashMap<Int, Int>>() // obra → provider → expira no tick
        val counters = Counters()

        fun memoryBytes(): Long =
            members.cardinality().toLong() * Wire.MEMBER_RECORD +
                providers.values.sumOf { it.size }.toLong() * Wire.PROVIDER_RECORD
    }

    init {
        repeat(initialN) { addNodeBootstrapped(fullView = true) }
        // estado inicial: rede já formada (full view); a formação fria é medida no join do churn
        obras.forEach { (obra, pubs) -> pubs.forEach { announce(it, obra) } }
    }

    fun aliveIndices(): List<Int> = nodes.indices.filter { nodes[it] != null }
    fun aliveCount(): Int = nodes.count { it != null }
    private fun node(i: Int): GossipNode? = nodes.getOrNull(i)

    /** Nó novo entra pelo bootstrap: recebe membership + providers (state transfer) e é anunciado por PEX. */
    fun addNodeBootstrapped(fullView: Boolean = false): Int {
        val idx = nodes.size
        val n = GossipNode(idx)
        nodes.add(n)
        n.members.set(idx)
        if (fullView) {
            // formação inicial: todos se conhecem (a priori, como a malha do poc-01 E5)
            for (j in 0 until idx) {
                node(j)?.members?.set(idx)
                n.members.set(j)
            }
        } else {
            val bootstrap = node(aliveIndices().first { it != idx })!!
            // custo do cold join: transferência de estado do bootstrap
            val stateBytes = bootstrap.members.cardinality().toLong() * Wire.MEMBER_RECORD +
                bootstrap.providers.values.sumOf { it.size }.toLong() * Wire.PROVIDER_RECORD
            bootstrap.counters.bytesOut += stateBytes
            n.counters.bytesIn += stateBytes
            n.members.or(bootstrap.members)
            bootstrap.providers.forEach { (obra, m) -> n.providers.getOrPut(obra) { HashMap() }.putAll(m) }
            bootstrap.members.set(idx) // bootstrap aprende o novato; o resto aprende via gossip
        }
        return idx
    }

    fun kill(idx: Int) {
        require(obras.values.none { idx in it }) { "cenário mantém publicadores vivos" }
        nodes[idx] = null
        deadAtTick[idx] = tick
    }

    private fun announce(publisher: Int, obra: String) {
        val pub = node(publisher) ?: return
        pub.providers.getOrPut(obra) { HashMap() }[publisher] = tick + PROVIDER_TTL_TICKS
        repeat(ANNOUNCE_FANOUT) {
            val peer = randomMember(pub) ?: return@repeat
            node(peer)?.let { target ->
                pub.counters.bytesOut += Wire.HEADER + Wire.PROVIDER_RECORD
                target.counters.bytesIn += Wire.HEADER + Wire.PROVIDER_RECORD
                target.providers.getOrPut(obra) { HashMap() }[publisher] = tick + PROVIDER_TTL_TICKS
            }
        }
    }

    private fun randomMember(from: GossipNode): Int? {
        val others = from.members.cardinality() - if (from.members.get(from.idx)) 1 else 0
        if (others <= 0) return null
        var pick = rng.nextInt(others)
        var i = from.members.nextSetBit(0)
        while (i >= 0) {
            if (i != from.idx) {
                if (pick == 0) return i
                pick--
            }
            i = from.members.nextSetBit(i + 1)
        }
        return null
    }

    private fun exchange(a: GossipNode, b: GossipNode) {
        // digests sempre trafegam
        a.counters.bytesOut += Wire.HEADER + Wire.DIGEST; b.counters.bytesIn += Wire.HEADER + Wire.DIGEST
        b.counters.bytesOut += Wire.HEADER + Wire.DIGEST; a.counters.bytesIn += Wire.HEADER + Wire.DIGEST

        // digests independentes por conjunto: só o conjunto divergente paga a lista compacta
        val sameMembers = a.members == b.members
        val sameProviders = a.providers == b.providers
        if (sameMembers && sameProviders) return

        if (!sameMembers) {
            val memberList = (a.members.cardinality() + b.members.cardinality()).toLong() * Wire.DIGEST_ENTRY
            a.counters.bytesOut += memberList / 2; b.counters.bytesIn += memberList / 2
            b.counters.bytesOut += memberList / 2; a.counters.bytesIn += memberList / 2
            val aMissing = (b.members.clone() as BitSet).apply { andNot(a.members) }
            val bMissing = (a.members.clone() as BitSet).apply { andNot(b.members) }
            transferMembers(b, a, aMissing.cardinality())
            transferMembers(a, b, bMissing.cardinality())
            a.members.or(b.members)
            b.members.or(a.members)
        }
        if (!sameProviders) {
            val providerList = (a.providers.values.sumOf { it.size } + b.providers.values.sumOf { it.size })
                .toLong() * Wire.DIGEST_ENTRY
            a.counters.bytesOut += providerList / 2; b.counters.bytesIn += providerList / 2
            b.counters.bytesOut += providerList / 2; a.counters.bytesIn += providerList / 2
            mergeProviders(b, a)
            mergeProviders(a, b)
        }
    }

    private fun transferMembers(from: GossipNode, to: GossipNode, count: Int) {
        val bytes = count.toLong() * Wire.MEMBER_RECORD
        from.counters.bytesOut += bytes
        to.counters.bytesIn += bytes
    }

    private fun mergeProviders(from: GossipNode, to: GossipNode) {
        var transferred = 0
        from.providers.forEach { (obra, recs) ->
            val mine = to.providers.getOrPut(obra) { HashMap() }
            recs.forEach { (prov, exp) ->
                val known = mine[prov]
                if (known == null || known < exp) {
                    mine[prov] = exp
                    transferred++
                }
            }
        }
        from.counters.bytesOut += transferred.toLong() * Wire.PROVIDER_RECORD
        to.counters.bytesIn += transferred.toLong() * Wire.PROVIDER_RECORD
    }

    fun doTick() {
        tick++

        // detecção de falha uniforme: morto some das visões FAIL_TTL depois
        val expired = deadAtTick.filterValues { tick - it == FAIL_TTL_TICKS }.keys
        expired.forEach { deadIdx ->
            nodes.forEach { it?.members?.clear(deadIdx) }
        }

        // expiração de providers
        nodes.forEach { n ->
            n?.providers?.values?.forEach { m -> m.entries.removeIf { it.value < tick } }
            n?.providers?.entries?.removeIf { it.value.isEmpty() }
        }

        // re-anúncio periódico dos publicadores
        if (tick % ANNOUNCE_EVERY_TICKS == 0) {
            obras.forEach { (obra, pubs) -> pubs.forEach { announce(it, obra) } }
        }

        // rodada de anti-entropia
        aliveIndices().forEach { i ->
            val a = node(i) ?: return@forEach
            repeat(FANOUT) {
                val peerIdx = randomMember(a) ?: return@repeat
                val b = node(peerIdx)
                if (b == null) {
                    // peer morto ainda não detectado: dial perdido (timeout)
                    a.counters.bytesOut += Wire.HEADER
                } else {
                    exchange(a, b)
                }
            }
        }
    }

    /** Visões de membership de todos os vivos idênticas ao conjunto vivo real. */
    fun membershipConverged(): Boolean {
        val alive = BitSet(nodes.size)
        aliveIndices().forEach { alive.set(it) }
        return aliveIndices().all { node(it)!!.members == alive }
    }

    // ---- lado do cliente (ADR-0005: fora da malha) ----

    inner class Client {
        val counters = Counters()
        var inboundRequests = 0L      // deve permanecer 0
        var storedRecords = 0L        // deve permanecer 0 (fora do escopo de um lookup)
        private var knownNodes: List<Int> = emptyList()

        /** Descoberta fria: só bootstrap + obraId. */
        fun coldLookup(obra: String, bootstrapIdx: Int): LookupResult {
            knownNodes = emptyList()
            var rtts = 0
            // RTT 1: PEX — amostra de membros do bootstrap
            val bootstrap = node(bootstrapIdx) ?: return LookupResult(1, emptyList(), false)
            rtts++
            counters.bytesOut += Wire.HEADER
            val sample = sampleMembers(bootstrap, BOOTSTRAP_SAMPLE)
            counters.bytesIn += Wire.HEADER + sample.size.toLong() * Wire.MEMBER_RECORD
            bootstrap.counters.bytesIn += Wire.HEADER
            bootstrap.counters.bytesOut += Wire.HEADER + sample.size.toLong() * Wire.MEMBER_RECORD
            bootstrap.counters.requestsServed++
            knownNodes = sample
            return query(obra, rtts)
        }

        /** Lookup quente: já conhece nós plenos de sessões anteriores. */
        fun warmLookup(obra: String): LookupResult {
            check(knownNodes.isNotEmpty()) { "warm lookup requer sessão anterior" }
            return query(obra, 0)
        }

        private fun query(obra: String, rttsSoFar: Int): LookupResult {
            var rtts = rttsSoFar
            var attempts = 0
            while (attempts < 5) {
                val candidates = knownNodes.shuffled(rng).take(2) // 2 em paralelo (merge do roadmap)
                if (candidates.isEmpty()) break
                rtts++ // consultas em paralelo = 1 RTT
                attempts++
                val results = candidates.mapNotNull { idx ->
                    val full = node(idx)
                    if (full == null) {
                        counters.bytesOut += Wire.HEADER // timeout
                        null
                    } else {
                        counters.bytesOut += Wire.HEADER + 32
                        full.counters.bytesIn += Wire.HEADER + 32
                        full.counters.requestsServed++
                        val recs = full.providers[obra]?.filterValues { it >= tick }?.keys.orEmpty().toList()
                        val bytes = Wire.HEADER + recs.size.toLong() * Wire.PROVIDER_RECORD
                        counters.bytesIn += bytes
                        full.counters.bytesOut += bytes
                        recs
                    }
                }.flatten().distinct()
                val aliveProviders = results.filter { node(it) != null }
                if (aliveProviders.isNotEmpty()) return LookupResult(rtts, aliveProviders, true)
                knownNodes = knownNodes.filter { node(it) != null }
            }
            return LookupResult(rtts, emptyList(), false)
        }

        private fun sampleMembers(from: GossipNode, count: Int): List<Int> {
            val all = ArrayList<Int>(from.members.cardinality())
            var i = from.members.nextSetBit(0)
            while (i >= 0) {
                if (i != from.idx) all.add(i)
                i = from.members.nextSetBit(i + 1)
            }
            all.shuffle(rng)
            return listOf(from.idx) + all.take(count - 1)
        }
    }
}
