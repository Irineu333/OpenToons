package org.opentoons.poc4.libp2p

import kotlinx.coroutines.runBlocking
import org.opentoons.poc4.api.Block
import org.opentoons.poc4.api.BootstrapAddr
import org.opentoons.poc4.api.Capability
import org.opentoons.poc4.api.ContentId
import org.opentoons.poc4.api.ObraId
import org.opentoons.poc4.api.P2pBackend
import org.opentoons.poc4.api.P2pException
import org.opentoons.poc4.api.Provider
import uniffi.facade.FacadeException
import uniffi.facade.Node

/**
 * PoC poc-04 — client RUST-LIBP2P atrás do seam `P2pBackend`: o facade do poc-03 (via
 * UniFFI) ligado à interface neutra. TODAS as conversões de conceito (BootstrapAddr →
 * multiaddr; chave Ed25519 → PeerId; ContentId → cid de request; wire length-prefixed →
 * blocos) vivem AQUI DENTRO — nada disso aparece na superfície (Q3).
 */
internal class Libp2pClient : P2pBackend {

    override val capabilities: Set<Capability> = setOf(Capability.HOLE_PUNCH)

    private val node = Node("") // client puro; bootstrap entra por dial()

    override fun dial(bootstrap: BootstrapAddr) = ffi("dial") {
        runBlocking { node.dial(multiaddrOf(bootstrap)) }
    }

    override fun resolve(obra: ObraId): List<Provider> = ffi("resolve") {
        val found = runBlocking { node.resolve(obra.value) }
        if (found.isEmpty()) emptyList()
        else listOf(Provider(id = found.substringAfterLast("/p2p/"), addresses = listOf(found)))
    }

    override fun getManifest(provider: Provider, obra: ObraId): ByteArray = ffi("getManifest") {
        runBlocking { node.getManifest(provider.addresses.first(), obra.value) }
    }

    override fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block> = ffi("getBlocks") {
        val wire = runBlocking {
            node.getBlocks(provider.addresses.first(), ids.joinToString("\n") { it.hex })
        }
        val slices = sliceLengthPrefixed(wire)
        if (slices.size != ids.size) {
            throw P2pException("esperados ${ids.size} blocos, vieram ${slices.size}")
        }
        ids.zip(slices) { id, bytes -> Block(id, bytes) }
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
