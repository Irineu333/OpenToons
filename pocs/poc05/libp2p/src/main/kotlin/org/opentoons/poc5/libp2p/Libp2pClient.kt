package org.opentoons.poc5.libp2p

import kotlinx.coroutines.runBlocking
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.Block
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.Capability
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.P2pBackend
import org.opentoons.poc5.api.P2pException
import org.opentoons.poc5.api.Provider
import uniffi.facade.FacadeException
import uniffi.facade.Node
import uniffi.facade.SocksProxy

/**
 * PoC poc-04 — client RUST-LIBP2P atrás do seam `P2pBackend`: o facade do poc-03 (via
 * UniFFI) ligado à interface neutra. TODAS as conversões de conceito (BootstrapAddr →
 * multiaddr; chave Ed25519 → PeerId; ContentId → cid de request; wire length-prefixed →
 * blocos) vivem AQUI DENTRO — nada disso aparece na superfície (Q3).
 */
internal class Libp2pClient(
    private val anonymity: AnonymityConfig = AnonymityConfig.DISABLED,
) : P2pBackend {

    // clearnet: HOLE_PUNCH (DCUtR+relay). anônimo: sem hole-punch (swarm contido, D6) →
    // só ANONYMOUS_DIAL. As capabilities refletem HONESTAMENTE o que cada modo tem.
    override val capabilities: Set<Capability> =
        if (anonymity.enabled) setOf(Capability.ANONYMOUS_DIAL) else setOf(Capability.HOLE_PUNCH)

    // modo anônimo (D2): o proxy SOCKS do daemon Tor entra como config de fábrica; o
    // transporte SOCKS-only + QUIC off + swarm contido é montado DENTRO do facade rust.
    private val node = Node(
        "",
        if (anonymity.enabled) SocksProxy(anonymity.socksHost!!, anonymity.socksPort!!.toUShort()) else null,
    )

    override fun dial(bootstrap: BootstrapAddr) = ffi("dial") {
        runBlocking { node.dial(multiaddrOf(bootstrap)) }
    }

    override fun resolve(obra: ObraId): List<Provider> = ffi("resolve") {
        val found = runBlocking { node.resolve(obra.value) }
        if (found.isEmpty()) emptyList()
        else {
            // C2 dual-homed: o facade devolve UMA multiaddr por linha (onion + IP público).
            val addrs = found.lines().filter { it.isNotBlank() }
            listOf(Provider(id = addrs.first().substringAfterLast("/p2p/"), addresses = addrs))
        }
    }

    /**
     * C2 dual-homed — escolhe o endereço alcançável POR ESTE client: o anônimo casa o onion
     * (`/dns/…onion`); o clearnet casa o `/ip4`. Fallback = primeiro endereço (caso C1).
     */
    private fun preferred(provider: Provider): String {
        val a = provider.addresses
        return if (anonymity.enabled) a.firstOrNull { it.contains("/dns/") && it.contains(".onion") } ?: a.first()
        else a.firstOrNull { it.contains("/ip4/") } ?: a.first()
    }

    override fun getManifest(provider: Provider, obra: ObraId): ByteArray = ffi("getManifest") {
        runBlocking { node.getManifest(preferred(provider), obra.value) }
    }

    override fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block> = ffi("getBlocks") {
        val wire = runBlocking {
            node.getBlocks(preferred(provider), ids.joinToString("\n") { it.hex })
        }
        val slices = sliceLengthPrefixed(wire)
        if (slices.size != ids.size) {
            throw P2pException("esperados ${ids.size} blocos, vieram ${slices.size}")
        }
        ids.zip(slices) { id, bytes -> Block(id, bytes) }
    }

    override fun push(target: Provider, obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>) = ffi("push") {
        // o multiaddr do target (onion no modo anônimo) é o endereço opaco do provider;
        // o dial vai pelo SOCKS. Sem endereço de origem no wire (D1).
        runBlocking { node.push(preferred(target), obra.value, manifestBlock, blocks.map { it.bytes }) }
    }

    override fun close() = node.close()

    private inline fun <T> ffi(op: String, body: () -> T): T = try {
        body()
    } catch (e: FacadeException) {
        throw P2pException("$op: ${e.message}", e)
    }

    private fun sliceLengthPrefixed(bytes: ByteArray): List<ByteArray> {
        val buf = java.nio.ByteBuffer.wrap(bytes)
        val out = ArrayList<ByteArray>()
        while (buf.remaining() >= 4) {
            val len = buf.int
            require(len >= 0 && len <= buf.remaining()) { "length-prefix inválido: $len" }
            out.add(ByteArray(len).also { buf.get(it) })
        }
        require(buf.remaining() == 0) { "bytes residuais no buffer" }
        return out
    }
}
