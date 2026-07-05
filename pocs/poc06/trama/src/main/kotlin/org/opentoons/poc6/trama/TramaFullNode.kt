package org.opentoons.poc6.trama

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc6.api.AnnounceTuning
import org.opentoons.poc6.api.Block
import org.opentoons.poc6.api.Blockstore
import org.opentoons.poc6.api.BootstrapAddr
import org.opentoons.poc6.api.ContentId
import org.opentoons.poc6.api.FullNode
import org.opentoons.poc6.api.ListenSpec
import org.opentoons.poc6.api.NodeKeys
import org.opentoons.poc6.api.ObraId
import org.opentoons.poc6.api.PushPolicy
import org.opentoons.poc6.api.decodeHex
import org.opentoons.poc6.trama.wire.PushRequest
import org.opentoons.poc6.trama.wire.Ack
import org.opentoons.poc6.trama.wire.AnnounceRequest
import org.opentoons.poc6.trama.wire.BlobResponse
import org.opentoons.poc6.trama.wire.FrameTransport
import org.opentoons.poc6.trama.wire.GetBlockRequest
import org.opentoons.poc6.trama.wire.GetManifestRequest
import org.opentoons.poc6.trama.wire.HelloRequest
import org.opentoons.poc6.trama.wire.NoiseChannel
import org.opentoons.poc6.trama.wire.PexRequest
import org.opentoons.poc6.trama.wire.PexResponse
import org.opentoons.poc6.trama.wire.ResolveRequest
import org.opentoons.poc6.trama.wire.ResolveResponse
import org.opentoons.poc6.trama.wire.RpcCodec
import org.opentoons.poc6.trama.wire.RpcException
import org.opentoons.poc6.trama.wire.RpcPeer
import org.opentoons.poc6.trama.wire.RpcTypes
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * poc-06 — full node TRAMA sobre um [FrameTransport] injetado (design D2): a MESMA logica de
 * membership/PEX/anuncios/push do poc-05, agora indiferente ao transporte. Com [TcpTransport]
 * e o no clearnet do poc-04; com [SamTransport] e um no cuja identidade de REDE e uma
 * destination I2P — discavel por tunel, sem porta nem IP publico (o "NAT dissolvido" do D0).
 * O endereco vira string OPACA; nada aqui interpreta "IP x destination".
 */
internal class TramaFullNode(
    private val keys: NodeKeys,
    private val tuning: AnnounceTuning,
    private val transport: FrameTransport,
) : FullNode {

    /** Endereco de rede opaco de um no: `host:port` (TCP) ou destination base64 (I2P). */
    internal data class NodeAddress(val idHex: String, val address: String) {
        val publicKey: Ed25519PublicKeyParameters
            get() = Ed25519PublicKeyParameters(idHex.decodeHex(), 0)

        override fun toString() = "$idHex@$address"

        companion object {
            fun parse(s: String): NodeAddress {
                val (id, addr) = s.split("@", limit = 2)
                return NodeAddress(id, addr)
            }

            /** BootstrapAddr -> endereco opaco: TCP combina host:port; I2P (port 0) usa host cru. */
            fun of(b: BootstrapAddr): NodeAddress =
                NodeAddress(b.publicKeyHex, if (b.port != 0) "${b.host}:${b.port}" else b.host)
        }
    }

    override val idHex: String get() = keys.idHex

    private val members = ConcurrentHashMap<String, NodeAddress>()
    private val providers = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()
    private val announcedObras = ConcurrentHashMap.newKeySet<String>()
    private val advertised = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val acceptedPublishers = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var store: Blockstore? = null
    @Volatile private var closed = false
    @Volatile private var started = false
    @Volatile private var self: NodeAddress? = null

    override val boundPort: Int
        get() = self?.address?.substringAfterLast(":", "")?.toIntOrNull() ?: 0

    override fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>) {
        check(!started) { "start() ja chamado" }
        started = true
        bootstrap.forEach { members[it.publicKeyHex] = NodeAddress.of(it) }
        transport.listen { raw ->
            val secure = NoiseChannel.serverHandshake(raw, keys)
            println("CONN ${secure.remoteDescription}")
            RpcPeer(secure, ::handle).use { it.awaitClose() }
        }
        self = NodeAddress(keys.idHex, transport.localAddress)
        thread(isDaemon = true, name = "poc6-trama-mesh") {
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
        checkNotNull(self) { "start() antes de announce()" }
        providers.getOrPut(obra.value) { ConcurrentHashMap() }[providerString()] =
            System.currentTimeMillis() + tuning.ttlMillis
        announcedObras.add(obra.value)
        thread(isDaemon = true) { runCatching { meshRound() } }
    }

    override fun acceptPushes(publisherKeyHex: String) {
        acceptedPublishers.add(publisherKeyHex.lowercase())
    }

    override fun selfProvider(): org.opentoons.poc6.api.Provider {
        val me = checkNotNull(self) { "start() antes de selfProvider()" }
        return org.opentoons.poc6.api.Provider(keys.idHex, listOf(me.address))
    }

    override fun advertise(address: String) {
        advertised.addIfAbsent(address)
    }

    private fun providerString(): String {
        val me = checkNotNull(self)
        val addrs = advertised + listOf(me.address)
        return "${me.idHex}@" + addrs.joinToString("|")
    }

    override fun stop() {
        closed = true
        runCatching { transport.close() }
    }

    private fun handle(type: Byte, body: ByteArray): Pair<Byte, ByteArray> = when (type) {
        RpcTypes.PEX -> {
            val req = RpcCodec.decode(PexRequest.serializer(), body)
            val sample = (listOfNotNull(self) + members.values.shuffled()).take(req.limit)
            RpcTypes.PEX to RpcCodec.encode(PexResponse.serializer(), PexResponse(sample.map { it.toString() }))
        }
        RpcTypes.HELLO -> {
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
        RpcTypes.PUSH -> {
            val req = RpcCodec.decode(PushRequest.serializer(), body)
            val s = checkNotNull(store) { "serve() antes de aceitar push" }
            val accepted = acceptedPublishers.any { PushPolicy.accepts(req.manifestBlock, it) }
            if (!accepted) {
                println("PUSH REJEITADO obra=${req.obraId} (editora nao aceita / assinatura invalida)")
                throw RpcException("push rejeitado: manifesto de chave nao aceita")
            }
            s.putManifest(ObraId(req.obraId), req.manifestBlock)
            req.blocks.forEach { s.putBlock(Block.of(it.bytes)) }
            println("PUSH ACEITO obra=${req.obraId} blocos=${req.blocks.size}")
            RpcTypes.PUSH to RpcCodec.encode(Ack.serializer(), Ack())
        }
        else -> throw RpcException("operacao desconhecida: $type")
    }

    private fun meshRound() {
        val me = self ?: return
        val now = System.currentTimeMillis()
        providers.values.forEach { m -> m.entries.removeIf { it.value <= now } }
        val prov = providerString()
        announcedObras.forEach { obra ->
            providers.getOrPut(obra) { ConcurrentHashMap() }[prov] = now + tuning.ttlMillis
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
                                AnnounceRequest(obra, prov, tuning.ttlMillis),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun dialRpc(peer: NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(
            transport.dial(peer.address), keys, peer.publicKey,
        )
        return RpcPeer(secure)
    }
}
