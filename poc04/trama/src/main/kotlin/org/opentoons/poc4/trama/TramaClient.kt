package org.opentoons.poc4.trama

import org.opentoons.poc4.api.Block
import org.opentoons.poc4.api.BootstrapAddr
import org.opentoons.poc4.api.Capability
import org.opentoons.poc4.api.ContentId
import org.opentoons.poc4.api.NodeKeys
import org.opentoons.poc4.api.ObraId
import org.opentoons.poc4.api.P2pBackend
import org.opentoons.poc4.api.P2pException
import org.opentoons.poc4.api.Provider
import org.opentoons.poc4.trama.wire.BlobResponse
import org.opentoons.poc4.trama.wire.GetBlockRequest
import org.opentoons.poc4.trama.wire.GetManifestRequest
import org.opentoons.poc4.trama.wire.NoiseChannel
import org.opentoons.poc4.trama.wire.PexRequest
import org.opentoons.poc4.trama.wire.PexResponse
import org.opentoons.poc4.trama.wire.ResolveRequest
import org.opentoons.poc4.trama.wire.ResolveResponse
import org.opentoons.poc4.trama.wire.RpcCodec
import org.opentoons.poc4.trama.wire.RpcPeer
import org.opentoons.poc4.trama.wire.RpcTypes
import org.opentoons.poc4.trama.wire.TcpClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * PoC poc-04 — client TRAMA atrás do seam `P2pBackend`: o ClientSession do poc-02
 * refatorado. Só conexões de SAÍDA, sem HELLO, sem estado persistente (ADR-0005).
 * Descoberta fria: PEX + RESOLVE no bootstrap (+ fallback em 2 nós da amostra PEX) —
 * o mecanismo (gossip) fica todo AQUI DENTRO; o app só vê `resolve(obraId)`.
 */
internal class TramaClient(private val keys: NodeKeys) : P2pBackend {

    override val capabilities: Set<Capability> = emptySet()

    private val bootstraps = CopyOnWriteArrayList<TramaFullNode.NodeAddress>()

    override fun dial(bootstrap: BootstrapAddr) {
        val addr = TramaFullNode.NodeAddress(bootstrap.publicKeyHex, bootstrap.host, bootstrap.port)
        // valida alcançabilidade + identidade já no dial (handshake Noise completo)
        runCatching { dialRpc(addr).close() }
            .onFailure { throw P2pException("bootstrap inalcançável: ${bootstrap.host}:${bootstrap.port}", it) }
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

                // fallback: pergunta a até 2 outros nós plenos da amostra PEX
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
        // todos os GET_BLOCK em paralelo na MESMA conexão (correlação por request-id)
        val futures = ids.map { id ->
            id to rpc.callAsync(RpcTypes.GET_BLOCK, RpcCodec.encode(GetBlockRequest.serializer(), GetBlockRequest(id.hex)))
        }
        futures.map { (id, future) ->
            val (type, body) = future.get(30, java.util.concurrent.TimeUnit.SECONDS)
            if (type == (RpcTypes.ERROR.toInt() and 0x7f).toByte()) {
                throw P2pException("bloco indisponível: ${id.hex}")
            }
            Block(id, RpcCodec.decode(BlobResponse.serializer(), body).bytes)
        }
    }

    override fun close() {
        bootstraps.clear() // nada persistente: conexões são por operação (ADR-0005)
    }

    private fun <T> withProvider(provider: Provider, op: (RpcPeer) -> T): T {
        val failures = mutableListOf<Throwable>()
        provider.addresses.forEach { address ->
            val (host, port) = address.split(":", limit = 2)
            val addr = TramaFullNode.NodeAddress(provider.id, host, port.toInt())
            runCatching { return dialRpc(addr).use(op) }.onFailure { failures += it }
        }
        throw P2pException(
            "nenhum endereço do provider respondeu: ${provider.addresses}",
            failures.lastOrNull(),
        )
    }

    private fun dialRpc(peer: TramaFullNode.NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            TcpClient.dial(peer.host, peer.port), keys, peer.publicKey,
        )
        return RpcPeer(secure)
    }

    private fun String.toProvider(): Provider {
        val addr = TramaFullNode.NodeAddress.parse(this)
        return Provider(id = addr.idHex, addresses = listOf("${addr.host}:${addr.port}"))
    }
}
