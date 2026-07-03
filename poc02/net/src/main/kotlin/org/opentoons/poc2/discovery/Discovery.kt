package org.opentoons.poc2.discovery

/**
 * E3 — interface comum de descoberta (design D3): o resto da stack só depende disto,
 * então gossip (E3a), Kademlia (E3b) ou uma DHT futura (marco 4) são intercambiáveis.
 */
data class Provider(
    val nodeIdHex: String,
    val address: String,
    val expiresAtMs: Long,
)

interface Discovery {
    fun resolve(obraId: String): List<Provider>
}
