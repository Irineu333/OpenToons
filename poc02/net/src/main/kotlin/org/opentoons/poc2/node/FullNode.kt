package org.opentoons.poc2.node

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.core.hexToBytes
import org.opentoons.poc2.core.toHex
import org.opentoons.poc2.discovery.Provider
import org.opentoons.poc2.noise.NoiseChannel
import org.opentoons.poc2.rpc.Ack
import org.opentoons.poc2.rpc.AnnounceRequest
import org.opentoons.poc2.rpc.BlobResponse
import org.opentoons.poc2.rpc.ChapterService
import org.opentoons.poc2.rpc.GetBlockRequest
import org.opentoons.poc2.rpc.GetManifestRequest
import org.opentoons.poc2.rpc.HelloRequest
import org.opentoons.poc2.rpc.PexRequest
import org.opentoons.poc2.rpc.PexResponse
import org.opentoons.poc2.rpc.ResolveRequest
import org.opentoons.poc2.rpc.ResolveResponse
import org.opentoons.poc2.rpc.RpcCodec
import org.opentoons.poc2.rpc.RpcException
import org.opentoons.poc2.rpc.RpcPeer
import org.opentoons.poc2.rpc.RpcTypes
import org.opentoons.poc2.transport.TcpClient
import org.opentoons.poc2.transport.TcpServer
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * E4 — nó pleno REAL da descoberta gossip escolhida no E3, sobre o canal Noise do E1b
 * (zero dependência de plataforma; a decisão final TLS×Noise sai da matriz 4.1).
 *
 * Membership + PEX + anúncios com TTL sobre o RPC do E2; malha por dials de SAÍDA
 * rediscada periodicamente (lição do E5 do poc-01 — não dependa de entrada para formar
 * malha). Clientes usam só PEX/RESOLVE/GET_*; HELLO de cliente não existe (ADR-0005).
 */
class FullNode(
    val identity: NodeIdentity,
    /** Endereço público manual (ADR-0006), ex.: "177.203.17.5:4101". */
    val publicAddress: String,
    listenPort: Int,
    bootstrap: List<NodeAddress> = emptyList(),
    private val meshIntervalMs: Long = 15_000,
    private val announceTtlMs: Long = 5 * 60_000,
) : Closeable {

    data class NodeAddress(val idHex: String, val host: String, val port: Int) {
        val publicKey: Ed25519PublicKeyParameters get() = Ed25519PublicKeyParameters(idHex.hexToBytes(), 0)
        override fun toString() = "$idHex@$host:$port"

        companion object {
            fun parse(s: String): NodeAddress {
                val (id, hostPort) = s.split("@", limit = 2)
                val (host, port) = hostPort.split(":", limit = 2)
                return NodeAddress(id, host, port.toInt())
            }
        }
    }

    private val members = ConcurrentHashMap<String, NodeAddress>() // idHex → endereço
    private val providers = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>() // obra → provider → expira em (ms)
    val chapterPublisher = ChapterService.Publisher(identity)
    private val announcedObras = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var closed = false

    private val server = TcpServer(listenPort) { raw ->
        val secure = NoiseChannel.serverHandshake(raw, identity)
        RpcPeer(secure, ::handle).use { it.awaitClose() }
    }

    val port: Int get() = server.port

    /** Porta 0 no endereço público = usar a porta efetiva do listener (testes locais). */
    val self: NodeAddress = publicAddress.substringAfter(":").toInt().let { declared ->
        NodeAddress(
            identity.idHex,
            publicAddress.substringBefore(":"),
            if (declared == 0) server.port else declared,
        )
    }

    private val mesh = thread(isDaemon = true, name = "poc2-mesh-${self.port}") {
        while (!closed) {
            runCatching { meshRound() }
            Thread.sleep(meshIntervalMs)
        }
    }

    init {
        bootstrap.forEach { members[it.idHex] = it }
    }

    private fun handle(type: Byte, body: ByteArray): Pair<Byte, ByteArray> = when (type) {
        RpcTypes.PEX -> {
            val req = RpcCodec.decode(PexRequest.serializer(), body)
            val sample = (listOf(self) + members.values.shuffled()).take(req.limit)
            RpcTypes.PEX to RpcCodec.encode(PexResponse.serializer(), PexResponse(sample.map { it.toString() }))
        }
        RpcTypes.HELLO -> {
            // só nós plenos se anunciam; a identidade do canal JÁ foi provada no handshake
            val member = NodeAddress.parse(RpcCodec.decode(HelloRequest.serializer(), body).member)
            members[member.idHex] = member
            RpcTypes.HELLO to RpcCodec.encode(Ack.serializer(), Ack())
        }
        RpcTypes.ANNOUNCE -> {
            val req = RpcCodec.decode(AnnounceRequest.serializer(), body)
            providers.getOrPut(req.obraId) { ConcurrentHashMap() }[req.provider] =
                System.currentTimeMillis() + req.ttlMs
            RpcTypes.ANNOUNCE to RpcCodec.encode(Ack.serializer(), Ack())
        }
        RpcTypes.RESOLVE -> {
            val req = RpcCodec.decode(ResolveRequest.serializer(), body)
            val now = System.currentTimeMillis()
            val live = providers[req.obraId].orEmpty().filterValues { it > now }.keys.toList()
            RpcTypes.RESOLVE to RpcCodec.encode(ResolveResponse.serializer(), ResolveResponse(live))
        }
        RpcTypes.GET_MANIFEST, RpcTypes.GET_BLOCK -> chapterPublisher.handle(type, body)
        else -> throw RpcException("operação desconhecida: $type")
    }

    /** Rodada de malha: rediscar membros conhecidos (saída), HELLO + PEX + re-anúncios. */
    private fun meshRound() {
        val now = System.currentTimeMillis()
        providers.values.forEach { m -> m.entries.removeIf { it.value <= now } }

        members.values.toList().forEach { peer ->
            runCatching {
                dialRpc(peer).use { rpc ->
                    rpc.call(RpcTypes.HELLO, RpcCodec.encode(HelloRequest.serializer(), HelloRequest(self.toString())))
                    val pex = RpcCodec.decode(
                        PexResponse.serializer(),
                        rpc.call(RpcTypes.PEX, RpcCodec.encode(PexRequest.serializer(), PexRequest())).second,
                    )
                    pex.members.map { NodeAddress.parse(it) }
                        .filter { it.idHex != self.idHex }
                        .forEach { members.putIfAbsent(it.idHex, it) }
                    announcedObras.forEach { obra ->
                        rpc.call(
                            RpcTypes.ANNOUNCE,
                            RpcCodec.encode(
                                AnnounceRequest.serializer(),
                                AnnounceRequest(obra, self.toString(), announceTtlMs),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun dialRpc(peer: NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            TcpClient.dial(peer.host, peer.port), identity, peer.publicKey,
        )
        return RpcPeer(secure)
    }

    /** Publica um capítulo e passa a anunciar a obra na malha. */
    fun publishChapter(obraId: String, chapterId: String, seq: Long, pages: List<ByteArray>) {
        chapterPublisher.publishChapter(chapterId, seq, pages)
        providers.getOrPut(obraId) { ConcurrentHashMap() }[self.toString()] =
            System.currentTimeMillis() + announceTtlMs
        announcedObras.add(obraId)
    }

    /** Força uma rodada de malha agora (testes e bootstrap rápido). */
    fun meshNow() = meshRound()

    fun knownMembers(): List<NodeAddress> = members.values.toList()

    override fun close() {
        closed = true
        server.close()
    }
}

/**
 * E4 — cliente móvel: SÓ conexões de saída, nada de HELLO, nada de estado persistente
 * (ADR-0005). Descoberta fria: bootstrap → PEX → RESOLVE → dial no provider.
 */
class ClientSession(private val identity: NodeIdentity) {

    data class Discovery(val providers: List<FullNode.NodeAddress>, val rtts: Int)

    fun coldDiscover(bootstrap: FullNode.NodeAddress, obraId: String): Discovery {
        var rtts = 0
        dial(bootstrap).use { rpc ->
            rtts++ // PEX no bootstrap
            val pex = RpcCodec.decode(
                PexResponse.serializer(),
                rpc.call(RpcTypes.PEX, RpcCodec.encode(PexRequest.serializer(), PexRequest())).second,
            ).members.map { FullNode.NodeAddress.parse(it) }

            rtts++ // RESOLVE no mesmo bootstrap (qualquer nó pleno serve)
            val resolved = RpcCodec.decode(
                ResolveResponse.serializer(),
                rpc.call(RpcTypes.RESOLVE, RpcCodec.encode(ResolveRequest.serializer(), ResolveRequest(obraId))).second,
            ).providers
            if (resolved.isNotEmpty()) {
                return Discovery(resolved.map { FullNode.NodeAddress.parse(it) }, rtts)
            }
            // fallback: pergunta a outro nó pleno da amostra PEX
            for (member in pex.filter { it.idHex != bootstrap.idHex }.take(2)) {
                rtts++
                val fromOther = runCatching {
                    dial(member).use { other ->
                        RpcCodec.decode(
                            ResolveResponse.serializer(),
                            other.call(
                                RpcTypes.RESOLVE,
                                RpcCodec.encode(ResolveRequest.serializer(), ResolveRequest(obraId)),
                            ).second,
                        ).providers
                    }
                }.getOrDefault(emptyList())
                if (fromOther.isNotEmpty()) {
                    return Discovery(fromOther.map { FullNode.NodeAddress.parse(it) }, rtts)
                }
            }
        }
        return Discovery(emptyList(), rtts)
    }

    /** Baixa e verifica o capítulo de um provider descoberto (nunca informado a priori). */
    fun fetchVerified(
        provider: FullNode.NodeAddress,
        chapterId: String,
        publisherKey: Ed25519PublicKeyParameters,
    ): List<ByteArray> = dial(provider).use { rpc ->
        ChapterService.Fetcher(rpc, publisherKey).fetchChapter(chapterId)
    }

    private fun dial(peer: FullNode.NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            TcpClient.dial(peer.host, peer.port), identity, peer.publicKey,
        )
        return RpcPeer(secure)
    }
}
