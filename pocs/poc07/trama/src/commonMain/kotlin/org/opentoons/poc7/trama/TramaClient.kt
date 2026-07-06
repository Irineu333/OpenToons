package org.opentoons.poc7.trama

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.opentoons.poc7.api.Block
import org.opentoons.poc7.api.BootstrapAddr
import org.opentoons.poc7.api.Capability
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.NodeKeys
import org.opentoons.poc7.api.ObraId
import org.opentoons.poc7.api.P2pBackend
import org.opentoons.poc7.api.P2pException
import org.opentoons.poc7.api.Provider
import org.opentoons.poc7.trama.wire.BlobResponse
import org.opentoons.poc7.trama.wire.FrameTransport
import org.opentoons.poc7.trama.wire.GetBlockRequest
import org.opentoons.poc7.trama.wire.GetManifestRequest
import org.opentoons.poc7.trama.wire.NoiseChannel
import org.opentoons.poc7.trama.wire.PexRequest
import org.opentoons.poc7.trama.wire.PexResponse
import org.opentoons.poc7.trama.wire.PushBlock
import org.opentoons.poc7.trama.wire.PushRequest
import org.opentoons.poc7.trama.wire.ResolveRequest
import org.opentoons.poc7.trama.wire.ResolveResponse
import org.opentoons.poc7.trama.wire.RpcCodec
import org.opentoons.poc7.trama.wire.RpcPeer
import org.opentoons.poc7.trama.wire.RpcTypes

/**
 * poc-07 — client TRAMA portado para KMP: só conexões de SAÍDA, sobre um [FrameTransport]
 * injetado (TCP no clearnet). A lógica de descoberta (PEX + RESOLVE) é IDÊNTICA ao poc-06; o
 * que muda é a mecânica (coroutines/ktor sob a API bloqueante do [P2pBackend]). É este o
 * leitor que roda no iPhone (Kotlin/Native) na campanha.
 */
internal class TramaClient(
    private val keys: NodeKeys,
    private val transport: FrameTransport,
    anonymous: Boolean,
) : P2pBackend {

    private val dialTimeoutMs: Long = 45_000

    override val capabilities: Set<Capability> =
        if (anonymous) setOf(Capability.ANONYMOUS_DIAL) else emptySet()

    private val lock = SynchronizedObject()
    private val bootstraps = ArrayList<TramaFullNode.NodeAddress>()

    override fun dial(bootstrap: BootstrapAddr) {
        val addr = TramaFullNode.NodeAddress.of(bootstrap)
        runCatching { runBlocking { dialRpc(addr).close() } }
            .onFailure { throw P2pException("bootstrap inalcançável: ${bootstrap.host}", it) }
        synchronized(lock) { if (bootstraps.none { it.idHex == addr.idHex }) bootstraps.add(addr) }
    }

    override fun resolve(obra: ObraId): List<Provider> {
        val first = synchronized(lock) { bootstraps.firstOrNull() }
            ?: throw P2pException("dial(bootstrap) antes de resolve()")
        return try {
            runBlocking {
                withPeer(first) { rpc ->
                    val pex = RpcCodec.decode(
                        PexResponse.serializer(),
                        rpc.call(RpcTypes.PEX, RpcCodec.encode(PexRequest.serializer(), PexRequest())).second,
                    ).members.map { TramaFullNode.NodeAddress.parse(it) }

                    val resolved = RpcCodec.decode(
                        ResolveResponse.serializer(),
                        rpc.call(RpcTypes.RESOLVE, RpcCodec.encode(ResolveRequest.serializer(), ResolveRequest(obra.value))).second,
                    ).providers
                    if (resolved.isNotEmpty()) return@withPeer resolved.map { it.toProvider() }

                    for (member in pex.filter { it.idHex != first.idHex }.take(2)) {
                        val fromOther = runCatching {
                            withPeer(member) { other ->
                                RpcCodec.decode(
                                    ResolveResponse.serializer(),
                                    other.call(RpcTypes.RESOLVE, RpcCodec.encode(ResolveRequest.serializer(), ResolveRequest(obra.value))).second,
                                ).providers
                            }
                        }.getOrDefault(emptyList())
                        if (fromOther.isNotEmpty()) return@withPeer fromOther.map { it.toProvider() }
                    }
                    emptyList()
                }
            }
        } catch (e: P2pException) {
            throw e
        } catch (e: Exception) {
            throw P2pException("resolve falhou: ${e.message}", e)
        }
    }

    override fun getManifest(provider: Provider, obra: ObraId): ByteArray = runBlocking {
        withProvider(provider) { rpc ->
            RpcCodec.decode(
                BlobResponse.serializer(),
                rpc.call(RpcTypes.GET_MANIFEST, RpcCodec.encode(GetManifestRequest.serializer(), GetManifestRequest(obra.value))).second,
            ).bytes
        }
    }

    override fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block> = runBlocking {
        withProvider(provider) { rpc ->
            val deferreds = ids.map { id ->
                id to rpc.callAsync(RpcTypes.GET_BLOCK, RpcCodec.encode(GetBlockRequest.serializer(), GetBlockRequest(id.hex)))
            }
            deferreds.map { (id, d) ->
                val (type, body) = withTimeout(60_000) { d.await() }
                if (type == RpcTypes.ERROR) throw P2pException("bloco indisponível: ${id.hex}")
                Block(id, RpcCodec.decode(BlobResponse.serializer(), body).bytes)
            }
        }
    }

    override fun push(target: Provider, obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>) {
        val req = PushRequest(obra.value, manifestBlock, blocks.map { PushBlock(it.bytes) })
        runBlocking {
            withProvider(target) { rpc ->
                rpc.call(RpcTypes.PUSH, RpcCodec.encode(PushRequest.serializer(), req))
            }
        }
    }

    override fun close() {
        synchronized(lock) { bootstraps.clear() }
        runCatching { transport.close() }
    }

    private suspend fun <T> withProvider(provider: Provider, op: suspend (RpcPeer) -> T): T {
        var last: Throwable? = null
        for (address in provider.addresses) {
            val addr = TramaFullNode.NodeAddress(provider.id, address)
            try {
                val rpc = dialRpc(addr)
                try { return op(rpc) } finally { rpc.close() }
            } catch (e: Throwable) { last = e }
        }
        throw P2pException("nenhum endereço do provider respondeu: ${provider.addresses}", last)
    }

    private suspend fun <T> withPeer(peer: TramaFullNode.NodeAddress, op: suspend (RpcPeer) -> T): T {
        val rpc = dialRpc(peer)
        return try { op(rpc) } finally { rpc.close() }
    }

    private suspend fun dialRpc(peer: TramaFullNode.NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            transport.dial(peer.address, dialTimeoutMs), keys, peer.publicKeyBytes,
        )
        return RpcPeer(secure)
    }

    private fun String.toProvider(): Provider {
        val (id, addrsPart) = this.split("@", limit = 2)
        return Provider(id = id, addresses = addrsPart.split("|").filter { it.isNotEmpty() })
    }
}
