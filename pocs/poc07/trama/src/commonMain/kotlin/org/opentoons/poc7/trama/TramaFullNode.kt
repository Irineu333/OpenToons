package org.opentoons.poc7.trama

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.opentoons.poc7.api.AnnounceTuning
import org.opentoons.poc7.api.Block
import org.opentoons.poc7.api.Blockstore
import org.opentoons.poc7.api.BootstrapAddr
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.FullNode
import org.opentoons.poc7.api.ListenSpec
import org.opentoons.poc7.api.NodeKeys
import org.opentoons.poc7.api.ObraId
import org.opentoons.poc7.api.Provider
import org.opentoons.poc7.api.PushPolicy
import org.opentoons.poc7.api.decodeHex
import org.opentoons.poc7.trama.wire.Ack
import org.opentoons.poc7.trama.wire.AnnounceRequest
import org.opentoons.poc7.trama.wire.BlobResponse
import org.opentoons.poc7.trama.wire.FrameTransport
import org.opentoons.poc7.trama.wire.GetBlockRequest
import org.opentoons.poc7.trama.wire.GetManifestRequest
import org.opentoons.poc7.trama.wire.HelloRequest
import org.opentoons.poc7.trama.wire.NoiseChannel
import org.opentoons.poc7.trama.wire.PexRequest
import org.opentoons.poc7.trama.wire.PexResponse
import org.opentoons.poc7.trama.wire.PushRequest
import org.opentoons.poc7.trama.wire.ResolveRequest
import org.opentoons.poc7.trama.wire.ResolveResponse
import org.opentoons.poc7.trama.wire.RpcCodec
import org.opentoons.poc7.trama.wire.RpcException
import org.opentoons.poc7.trama.wire.RpcPeer
import org.opentoons.poc7.trama.wire.RpcTypes

/**
 * poc-07 — full node TRAMA portado para KMP: MESMA lógica de membership/PEX/anúncios/push do
 * poc-06, agora sobre coroutines/ktor. Roda no alvo `jvm` (DEV/VPS) e — por ser `commonMain` —
 * também no Native (o TCK levanta full nodes no simulador iOS). `ConcurrentHashMap`→mapas sob
 * lock atomicfu; threads→coroutines; `System.currentTimeMillis`→[nowMillis].
 */
internal class TramaFullNode(
    private val keys: NodeKeys,
    private val tuning: AnnounceTuning,
    private val transport: FrameTransport,
) : FullNode {

    internal data class NodeAddress(val idHex: String, val address: String) {
        val publicKeyBytes: ByteArray get() = idHex.decodeHex()
        override fun toString() = "$idHex@$address"

        companion object {
            fun parse(s: String): NodeAddress {
                val (id, addr) = s.split("@", limit = 2)
                return NodeAddress(id, addr)
            }
            fun of(b: BootstrapAddr): NodeAddress =
                NodeAddress(b.publicKeyHex, if (b.port != 0) "${b.host}:${b.port}" else b.host)
        }
    }

    override val idHex: String get() = keys.idHex

    private val lock = SynchronizedObject()
    private val members = HashMap<String, NodeAddress>()
    private val providers = HashMap<String, HashMap<String, Long>>()
    private val announcedObras = HashSet<String>()
    private val advertised = ArrayList<String>()
    private val acceptedPublishers = HashSet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closed = atomic(false)
    private val started = atomic(false)
    private var store: Blockstore? = null
    private var self: NodeAddress? = null

    override val boundPort: Int
        get() = self?.address?.substringAfterLast(":", "")?.toIntOrNull() ?: 0

    override fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>) {
        check(started.compareAndSet(false, true)) { "start() já chamado" }
        synchronized(lock) { bootstrap.forEach { members[it.publicKeyHex] = NodeAddress.of(it) } }
        transport.listen { raw ->
            val secure = NoiseChannel.serverHandshake(raw, keys)
            val rpc = RpcPeer(secure, ::handle)
            try { rpc.awaitClose() } finally { rpc.close() }
        }
        self = NodeAddress(keys.idHex, transport.localAddress)
        scope.launch {
            while (isActive && !closed.value) {
                runCatching { meshRound() }
                delay(tuning.republishMillis)
            }
        }
    }

    override fun serve(store: Blockstore) { this.store = store }

    override fun publish(obra: ObraId, manifestBlock: ByteArray, blocks: List<Block>) {
        val s = checkNotNull(store) { "serve() antes de publish()" }
        s.putManifest(obra, manifestBlock)
        blocks.forEach { s.putBlock(it) }
    }

    override fun announce(obra: ObraId) {
        checkNotNull(self) { "start() antes de announce()" }
        synchronized(lock) {
            providers.getOrPut(obra.value) { HashMap() }[providerString()] = nowMillis() + tuning.ttlMillis
            announcedObras.add(obra.value)
        }
        scope.launch { runCatching { meshRound() } }
    }

    override fun acceptPushes(publisherKeyHex: String) {
        synchronized(lock) { acceptedPublishers.add(publisherKeyHex.lowercase()) }
    }

    override fun selfProvider(): Provider {
        val me = checkNotNull(self) { "start() antes de selfProvider()" }
        return Provider(keys.idHex, listOf(me.address))
    }

    override fun advertise(address: String) {
        synchronized(lock) { if (address !in advertised) advertised.add(address) }
    }

    private fun providerString(): String {
        val me = checkNotNull(self)
        val addrs = advertised + listOf(me.address)
        return "${me.idHex}@" + addrs.joinToString("|")
    }

    override fun stop() {
        closed.value = true
        runCatching { transport.close() }
        runCatching { scope.cancel() }
    }

    private suspend fun handle(type: Byte, body: ByteArray): Pair<Byte, ByteArray> = when (type) {
        RpcTypes.PEX -> {
            val req = RpcCodec.decode(PexRequest.serializer(), body)
            val sample = synchronized(lock) { (listOfNotNull(self) + members.values.shuffled()).take(req.limit) }
            RpcTypes.PEX to RpcCodec.encode(PexResponse.serializer(), PexResponse(sample.map { it.toString() }))
        }
        RpcTypes.HELLO -> {
            val member = NodeAddress.parse(RpcCodec.decode(HelloRequest.serializer(), body).member)
            synchronized(lock) { members[member.idHex] = member }
            RpcTypes.HELLO to RpcCodec.encode(Ack.serializer(), Ack())
        }
        RpcTypes.ANNOUNCE -> {
            val req = RpcCodec.decode(AnnounceRequest.serializer(), body)
            synchronized(lock) {
                providers.getOrPut(req.obraId) { HashMap() }[req.provider] = nowMillis() + req.ttlMs
            }
            RpcTypes.ANNOUNCE to RpcCodec.encode(Ack.serializer(), Ack())
        }
        RpcTypes.RESOLVE -> {
            val req = RpcCodec.decode(ResolveRequest.serializer(), body)
            val now = nowMillis()
            val live = synchronized(lock) { providers[req.obraId].orEmpty().filterValues { it > now }.keys.toList() }
            RpcTypes.RESOLVE to RpcCodec.encode(ResolveResponse.serializer(), ResolveResponse(live))
        }
        RpcTypes.GET_MANIFEST -> {
            val req = RpcCodec.decode(GetManifestRequest.serializer(), body)
            val manifest = store?.manifest(ObraId(req.obraId)) ?: throw RpcException("manifesto desconhecido: ${req.obraId}")
            RpcTypes.GET_MANIFEST to RpcCodec.encode(BlobResponse.serializer(), BlobResponse(manifest))
        }
        RpcTypes.GET_BLOCK -> {
            val req = RpcCodec.decode(GetBlockRequest.serializer(), body)
            val block = store?.block(ContentId(req.cid)) ?: throw RpcException("bloco desconhecido: ${req.cid}")
            RpcTypes.GET_BLOCK to RpcCodec.encode(BlobResponse.serializer(), BlobResponse(block))
        }
        RpcTypes.PUSH -> {
            val req = RpcCodec.decode(PushRequest.serializer(), body)
            val s = checkNotNull(store) { "serve() antes de aceitar push" }
            val accepted = synchronized(lock) { acceptedPublishers.any { PushPolicy.accepts(req.manifestBlock, it) } }
            if (!accepted) throw RpcException("push rejeitado: manifesto de chave não aceita")
            s.putManifest(ObraId(req.obraId), req.manifestBlock)
            req.blocks.forEach { s.putBlock(Block.of(it.bytes)) }
            RpcTypes.PUSH to RpcCodec.encode(Ack.serializer(), Ack())
        }
        else -> throw RpcException("operação desconhecida: $type")
    }

    private suspend fun meshRound() {
        val me = self ?: return
        val now = nowMillis()
        val prov = synchronized(lock) {
            providers.values.forEach { m -> m.entries.removeAll { it.value <= now } }
            val p = providerString()
            announcedObras.forEach { obra -> providers.getOrPut(obra) { HashMap() }[p] = now + tuning.ttlMillis }
            p
        }
        val peers = synchronized(lock) { members.values.toList() }
        val obras = synchronized(lock) { announcedObras.toList() }
        peers.forEach { peer ->
            runCatching {
                val rpc = dialRpc(peer)
                try {
                    rpc.call(RpcTypes.HELLO, RpcCodec.encode(HelloRequest.serializer(), HelloRequest(me.toString())))
                    val pex = RpcCodec.decode(
                        PexResponse.serializer(),
                        rpc.call(RpcTypes.PEX, RpcCodec.encode(PexRequest.serializer(), PexRequest())).second,
                    )
                    val fresh = pex.members.map { NodeAddress.parse(it) }.filter { it.idHex != me.idHex }
                    synchronized(lock) { fresh.forEach { if (it.idHex !in members) members[it.idHex] = it } }
                    obras.forEach { obra ->
                        rpc.call(RpcTypes.ANNOUNCE, RpcCodec.encode(AnnounceRequest.serializer(), AnnounceRequest(obra, prov, tuning.ttlMillis)))
                    }
                } finally { rpc.close() }
            }
        }
    }

    private suspend fun dialRpc(peer: NodeAddress): RpcPeer {
        val secure = NoiseChannel.clientHandshake(transport.dial(peer.address), keys, peer.publicKeyBytes)
        return RpcPeer(secure)
    }
}
