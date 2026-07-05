package org.opentoons.poc2.sim

import kotlin.random.Random

/**
 * E3 — base do harness de simulação in-process (design D3): tempo virtual em ticks,
 * contadores de tráfego por nó e custos de wire fixados a priori. A simulação substitui
 * o papel de validação em escala que a Amino cumpriu no poc-01.
 *
 * Idealizações assumidas (registradas no relatório):
 *  - a rede não perde mensagens entre nós vivos; RTT = 1 unidade por troca request/response;
 *  - detecção de falha é uniforme: um membro morto some das visões [FAIL_TTL_TICKS] ticks
 *    após a morte, desde que o nó tenha participado de gossip nesse meio-tempo;
 *  - tamanhos de mensagem derivados de registros com tamanho fixo (abaixo), não de
 *    serialização real — o E2 mede serialização de verdade.
 */
object Wire {
    const val HEADER = 16            // request-id + tipo + tamanho
    const val MEMBER_RECORD = 48     // 32 pubkey + endereço + expiry
    const val PROVIDER_RECORD = 80   // 32 hash da obra + 32 nodeId + endereço + expiry
    const val DIGEST = 40            // resumo (contagens + checksums) da anti-entropia
    const val DIGEST_ENTRY = 8       // hash truncado por entrada na comparação de conjuntos
}

/** Um tick = [TICK_SECONDS] de tempo real simulado. */
const val TICK_SECONDS = 10

class Counters {
    var bytesIn = 0L
    var bytesOut = 0L
    var requestsServed = 0L

    fun reset() {
        bytesIn = 0; bytesOut = 0; requestsServed = 0
    }
}

/** Resultado de um lookup do cliente na simulação. */
data class LookupResult(
    val rtts: Int,
    val providers: List<Int>, // índices de nós providers vivos retornados
    val success: Boolean,
)

/** Métricas agregadas de uma rodada de medição. */
data class VariantMetrics(
    val variant: String,
    val n: Int,
    val coldLookupRtts: Double,
    val warmLookupRtts: Double,
    val trafficPerNodePerHourKb: Double,
    val memoryPerNodeKb: Double,
    val convergenceAfterChurnSeconds: Int,
    val clientInboundRequests: Long,
    val clientStoredRecords: Long,
)

fun percent(x: Double): String = "%.1f".format(x)

/** RNG determinístico por cenário: as rodadas são reprodutíveis. */
fun rngFor(variant: String, n: Int): Random = Random((variant.hashCode() * 31L + n))
