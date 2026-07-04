package org.opentoons.poc4.trama

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc4.api.AnnounceTuning
import org.opentoons.poc4.api.Block
import org.opentoons.poc4.api.Blockstore
import org.opentoons.poc4.api.BootstrapAddr
import org.opentoons.poc4.api.ContentId
import org.opentoons.poc4.api.FullNode
import org.opentoons.poc4.api.ListenSpec
import org.opentoons.poc4.api.NodeKeys
import org.opentoons.poc4.api.ObraId
import org.opentoons.poc4.api.decodeHex
import org.opentoons.poc4.trama.wire.Ack
import org.opentoons.poc4.trama.wire.AnnounceRequest
import org.opentoons.poc4.trama.wire.BlobResponse
import org.opentoons.poc4.trama.wire.GetBlockRequest
import org.opentoons.poc4.trama.wire.GetManifestRequest
import org.opentoons.poc4.trama.wire.HelloRequest
import org.opentoons.poc4.trama.wire.NoiseChannel
import org.opentoons.poc4.trama.wire.PexRequest
import org.opentoons.poc4.trama.wire.PexResponse
import org.opentoons.poc4.trama.wire.ResolveRequest
import org.opentoons.poc4.trama.wire.ResolveResponse
import org.opentoons.poc4.trama.wire.RpcCodec
import org.opentoons.poc4.trama.wire.RpcException
import org.opentoons.poc4.trama.wire.RpcPeer
import org.opentoons.poc4.trama.wire.RpcTypes
import org.opentoons.poc4.trama.wire.TcpClient
import org.opentoons.poc4.trama.wire.TcpServer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * PoC poc-04 — full node TRAMA atrás do seam `FullNode` do `:api`: refatoração honesta do
 * nó do poc-02 (membership + PEX + anúncios com TTL sobre RPC/Noise, malha por dials de
 * SAÍDA rediscada). Expiry e republish são INTERNOS: o re-anúncio epidêmico acontece a
 * cada rodada de malha ([AnnounceTuning.republishMillis]) com TTL de
 * [AnnounceTuning.ttlMillis] — o app só declara O QUE anuncia.
 */
internal class TramaFullNode(
    private val keys: NodeKeys,
    private val tuning: AnnounceTuning,
) : FullNode {

    internal data class NodeAddress(val idHex: String, val host: String, val port: Int) {
        val publicKey: Ed25519PublicKeyParameters
            get() = Ed25519PublicKeyParameters(idHex.decodeHex(), 0)

        override fun toString() = "$idHex@$host:$port"

        companion object {
            fun parse(s: String): NodeAddress {
                val (id, hostPort) = s.split("@", limit = 2)
                val (host, port) = hostPort.split(":", limit = 2)
                return NodeAddress(id, host, port.toInt())
            }
        }
    }

    override val idHex: String get() = keys.idHex

    private val members = ConcurrentHashMap<String, NodeAddress>() // idHex → endereço
    private val providers = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>() // obra → provider → expira (ms)
    private val announcedObras = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var store: Blockstore? = null
    @Volatile private var closed = false
    @Volatile private var server: TcpServer? = null
    @Volatile private var self: NodeAddress? = null

    override val boundPort: Int
        get() = server?.port ?: error("start() ainda não foi chamado")

    override fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>) {
        check(server == null) { "start() já chamado" }
        bootstrap.forEach { members[it.publicKeyHex] = NodeAddress(it.publicKeyHex, it.host, it.port) }
        val srv = TcpServer(listen.port) { raw ->
            val secure = NoiseChannel.serverHandshake(raw, keys)
            // prova de conexão do S3/S4: o publicador confirma quem o discou (identidade
            // provada no handshake Noise + endereço remoto)
            println("CONN ${secure.remoteDescription}")
            RpcPeer(secure, ::handle).use { it.awaitClose() }
        }
        server = srv
        self = NodeAddress(
            keys.idHex,
            listen.publicHost ?: "127.0.0.1",
            listen.publicPort ?: srv.port,
        )
        thread(isDaemon = true, name = "poc4-trama-mesh-${srv.port}") {
            while (!closed) {
                runCatching { meshRound() }
                Thread.sleep(tuning.republishMillis)
            }
        }
    }

    override fun serve(store: Blockstore) {
        this.store = store
    }

    override fun publish(obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>) {
        val s = checkNotNull(store) { "serve() antes de publish()" }
        s.putManifest(obra, manifestBlock)
        blocks.forEach { s.putBlock(it) }
    }

    override fun announce(obra: ObraId) {
        val me = checkNotNull(self) { "start() antes de announce()" }
        providers.getOrPut(obra.value) { ConcurrentHashMap() }[me.toString()] =
            System.currentTimeMillis() + tuning.ttlMillis
        announcedObras.add(obra.value)
        thread(isDaemon = true) { runCatching { meshRound() } } // propaga sem esperar a rodada
    }

    override fun stop() {
        closed = true
        server?.close()
    }

    private fun handle(type: Byte, body: ByteArray): Pair<Byte, ByteArray> = when (type) {
        RpcTypes.PEX -> {
            val req = RpcCodec.decode(PexRequest.serializer(), body)
            val sample = (listOfNotNull(self) + members.values.shuffled()).take(req.limit)
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
        RpcTypes.GET_MANIFEST -> {
            val req = RpcCodec.decode(GetManifestRequest.serializer(), body)
            println("REQUEST GET_MANIFEST ${req.obraId}")
            val manifest = store?.manifest(ObraId(req.obraId))
                ?: throw RpcException("manifesto desconhecido: ${req.obraId}")
            RpcTypes.GET_MANIFEST to RpcCodec.encode(BlobResponse.serializer(), BlobResponse(manifest))
        }
        RpcTypes.GET_BLOCK -> {
            val req = RpcCodec.decode(GetBlockRequest.serializer(), body)
            val block = store?.block(ContentId(req.cid))
                ?: throw RpcException("bloco desconhecido: ${req.cid}")
            RpcTypes.GET_BLOCK to RpcCodec.encode(BlobResponse.serializer(), BlobResponse(block))
        }
        else -> throw RpcException("operação desconhecida: $type")
    }

    /** Rodada de malha: rediscar membros conhecidos (saída), HELLO + PEX + re-anúncios. */
    private fun meshRound() {
        val me = self ?: return
        val now = System.currentTimeMillis()
        providers.values.forEach { m -> m.entries.removeIf { it.value <= now } }
        // o próprio anúncio local também é renovado a cada rodada (republish)
        announcedObras.forEach { obra ->
            providers.getOrPut(obra) { ConcurrentHashMap() }[me.toString()] = now + tuning.ttlMillis
        }

        members.values.toList().forEach { peer ->
            runCatching {
                dialRpc(peer).use { rpc ->
                    rpc.call(RpcTypes.HELLO, RpcCodec.encode(HelloRequest.serializer(), HelloRequest(me.toString())))
                    val pex = RpcCodec.decode(
                        PexResponse.serializer(),
                        rpc.call(RpcTypes.PEX, RpcCodec.encode(PexRequest.serializer(), PexRequest())).second,
                    )
                    pex.members.map { NodeAddress.parse(it) }
                        .filter { it.idHex != me.idHex }
                        .forEach { members.putIfAbsent(it.idHex, it) }
                    announcedObras.forEach { obra ->
                        rpc.call(
                            RpcTypes.ANNOUNCE,
                            RpcCodec.encode(
                                AnnounceRequest.serializer(),
                                AnnounceRequest(obra, me.toString(), tuning.ttlMillis),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun dialRpc(peer: NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            TcpClient.dial(peer.host, peer.port), keys, peer.publicKey,
        )
        return RpcPeer(secure)
    }
}
