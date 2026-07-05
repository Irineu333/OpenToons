package org.opentoons.poc6.trama

import org.opentoons.poc6.api.Block
import org.opentoons.poc6.api.BootstrapAddr
import org.opentoons.poc6.api.Capability
import org.opentoons.poc6.api.ContentId
import org.opentoons.poc6.api.NodeKeys
import org.opentoons.poc6.api.ObraId
import org.opentoons.poc6.api.P2pBackend
import org.opentoons.poc6.api.P2pException
import org.opentoons.poc6.api.Provider
import org.opentoons.poc6.trama.wire.PushBlock
import org.opentoons.poc6.trama.wire.PushRequest
import org.opentoons.poc6.trama.wire.BlobResponse
import org.opentoons.poc6.trama.wire.FrameTransport
import org.opentoons.poc6.trama.wire.GetBlockRequest
import org.opentoons.poc6.trama.wire.GetManifestRequest
import org.opentoons.poc6.trama.wire.NoiseChannel
import org.opentoons.poc6.trama.wire.PexRequest
import org.opentoons.poc6.trama.wire.PexResponse
import org.opentoons.poc6.trama.wire.ResolveRequest
import org.opentoons.poc6.trama.wire.ResolveResponse
import org.opentoons.poc6.trama.wire.RpcCodec
import org.opentoons.poc6.trama.wire.RpcPeer
import org.opentoons.poc6.trama.wire.RpcTypes
import java.util.concurrent.CopyOnWriteArrayList

/**
 * poc-06 — client TRAMA sobre um [FrameTransport] injetado: so conexoes de SAIDA, sem estado
 * persistente (ADR-0005). Com [SamTransport] (sessao transiente) e o leitor/publicador que
 * disca POR DESTINATION I2P — o "anonimato de rede para todos os papeis" do D0 aplicado ao
 * consumidor. A logica de descoberta (PEX + RESOLVE) e IDENTICA ao poc-05: so o transporte
 * embaixo mudou.
 */
internal class TramaClient(
    private val keys: NodeKeys,
    private val transport: FrameTransport,
    /** true quando o transporte e I2P — expoe a capability ANONYMOUS_DIAL (o eixo do poc-06). */
    anonymous: Boolean,
) : P2pBackend {

    private val dialTimeoutMs: Int = 45_000

    override val capabilities: Set<Capability> =
        if (anonymous) setOf(Capability.ANONYMOUS_DIAL) else emptySet()

    private val bootstraps = CopyOnWriteArrayList<TramaFullNode.NodeAddress>()

    override fun dial(bootstrap: BootstrapAddr) {
        val addr = TramaFullNode.NodeAddress.of(bootstrap)
        runCatching { dialRpc(addr).close() }
            .onFailure { throw P2pException("bootstrap inalcancavel: ${bootstrap.host}", it) }
        bootstraps.addIfAbsent(addr)
    }

    override fun resolve(obra: ObraId): List<Provider> {
        if (bootstraps.isEmpty()) throw P2pException("dial(bootstrap) antes de resolve()")
        val first = bootstraps.first()
        return try {
            dialRpc(first).use { rpc ->
                val pex = RpcCodec.decode(
                    PexResponse.serializer(),
                    rpc.call(RpcTypes.PEX, RpcCodec.encode(PexRequest.serializer(), PexRequest())).second,
                ).members.map { TramaFullNode.NodeAddress.parse(it) }

                val resolved = RpcCodec.decode(
                    ResolveResponse.serializer(),
                    rpc.call(RpcTypes.RESOLVE, RpcCodec.encode(ResolveRequest.serializer(), ResolveRequest(obra.value))).second,
                ).providers
                if (resolved.isNotEmpty()) return resolved.map { it.toProvider() }

                for (member in pex.filter { it.idHex != first.idHex }.take(2)) {
                    val fromOther = runCatching {
                        dialRpc(member).use { other ->
                            RpcCodec.decode(
                                ResolveResponse.serializer(),
                                other.call(
                                    RpcTypes.RESOLVE,
                                    RpcCodec.encode(ResolveRequest.serializer(), ResolveRequest(obra.value)),
                                ).second,
                            ).providers
                        }
                    }.getOrDefault(emptyList())
                    if (fromOther.isNotEmpty()) return fromOther.map { it.toProvider() }
                }
                emptyList()
            }
        } catch (e: P2pException) {
            throw e
        } catch (e: Exception) {
            throw P2pException("resolve falhou: ${e.message}", e)
        }
    }

    override fun getManifest(provider: Provider, obra: ObraId): ByteArray = withProvider(provider) { rpc ->
        RpcCodec.decode(
            BlobResponse.serializer(),
            rpc.call(RpcTypes.GET_MANIFEST, RpcCodec.encode(GetManifestRequest.serializer(), GetManifestRequest(obra.value))).second,
        ).bytes
    }

    override fun getBlocks(provider: Provider, ids: List<ContentId>): List<Block> = withProvider(provider) { rpc ->
        val futures = ids.map { id ->
            id to rpc.callAsync(RpcTypes.GET_BLOCK, RpcCodec.encode(GetBlockRequest.serializer(), GetBlockRequest(id.hex)))
        }
        futures.map { (id, future) ->
            val (type, body) = future.get(60, java.util.concurrent.TimeUnit.SECONDS)
            if (type == (RpcTypes.ERROR.toInt() and 0x7f).toByte()) {
                throw P2pException("bloco indisponivel: ${id.hex}")
            }
            Block(id, RpcCodec.decode(BlobResponse.serializer(), body).bytes)
        }
    }

    override fun push(target: Provider, obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>) {
        val req = PushRequest(
            obraId = obra.value,
            manifestBlock = manifestBlock,
            blocks = blocks.map { PushBlock(it.bytes) },
        )
        withTarget(target) { rpc ->
            rpc.call(RpcTypes.PUSH, RpcCodec.encode(PushRequest.serializer(), req))
        }
    }

    override fun close() {
        bootstraps.clear()
        runCatching { transport.close() }
    }

    private fun <T> withTarget(target: Provider, op: (RpcPeer) -> T): T {
        val failures = mutableListOf<Throwable>()
        target.addresses.forEach { address ->
            val addr = TramaFullNode.NodeAddress(target.id, address)
            runCatching { return dialRpc(addr).use(op) }.onFailure { failures += it }
        }
        throw P2pException("push falhou para ${target.addresses}: ${failures.lastOrNull()?.message}", failures.lastOrNull())
    }

    private fun <T> withProvider(provider: Provider, op: (RpcPeer) -> T): T {
        val failures = mutableListOf<Throwable>()
        provider.addresses.forEach { address ->
            val addr = TramaFullNode.NodeAddress(provider.id, address)
            runCatching { return dialRpc(addr).use(op) }.onFailure { failures += it }
        }
        throw P2pException(
            "nenhum endereco do provider respondeu: ${provider.addresses}",
            failures.lastOrNull(),
        )
    }

    private fun dialRpc(peer: TramaFullNode.NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            transport.dial(peer.address, dialTimeoutMs), keys, peer.publicKey,
        )
        return RpcPeer(secure)
    }

    private fun String.toProvider(): Provider {
        val (id, addrsPart) = this.split("@", limit = 2)
        return Provider(id = id, addresses = addrsPart.split("|").filter { it.isNotEmpty() })
    }
}
