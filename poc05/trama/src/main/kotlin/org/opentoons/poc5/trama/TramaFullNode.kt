package org.opentoons.poc5.trama

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.Block
import org.opentoons.poc5.api.Blockstore
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.NodeKeys
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.PushPolicy
import org.opentoons.poc5.api.decodeHex
import org.opentoons.poc5.trama.wire.PushRequest
import org.opentoons.poc5.trama.wire.Ack
import org.opentoons.poc5.trama.wire.AnnounceRequest
import org.opentoons.poc5.trama.wire.BlobResponse
import org.opentoons.poc5.trama.wire.GetBlockRequest
import org.opentoons.poc5.trama.wire.GetManifestRequest
import org.opentoons.poc5.trama.wire.HelloRequest
import org.opentoons.poc5.trama.wire.NoiseChannel
import org.opentoons.poc5.trama.wire.PexRequest
import org.opentoons.poc5.trama.wire.PexResponse
import org.opentoons.poc5.trama.wire.ResolveRequest
import org.opentoons.poc5.trama.wire.ResolveResponse
import org.opentoons.poc5.trama.wire.RpcCodec
import org.opentoons.poc5.trama.wire.RpcException
import org.opentoons.poc5.trama.wire.RpcPeer
import org.opentoons.poc5.trama.wire.RpcTypes
import org.opentoons.poc5.trama.wire.TcpClient
import org.opentoons.poc5.trama.wire.TcpServer
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
    // poc-05 (C2 dual-homed): endereços EXTRA anunciados (ex.: onion) além do próprio.
    private val advertised = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val acceptedPublishers = ConcurrentHashMap.newKeySet<String>() // poc-05: editoras aceitas em push

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
        thread(isDaemon = true, name = "poc5-trama-mesh-${srv.port}") {
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
        thread(isDaemon = true) { runCatching { meshRound() } } // propaga sem esperar a rodada
    }

    override fun acceptPushes(publisherKeyHex: String) {
        acceptedPublishers.add(publisherKeyHex.lowercase())
    }

    override fun selfProvider(): org.opentoons.poc5.api.Provider {
        checkNotNull(self) { "start() antes de selfProvider()" }
        // endereço LOCAL discável (loopback), NÃO o publicHost anunciado (onion no E3): R é
        // dual-homed — o onion é o que P descobre por resolve; o reader co-localizado usa o
        // clearnet local, como o mobile usaria o IP público.
        return org.opentoons.poc5.api.Provider(keys.idHex, listOf("127.0.0.1:$boundPort"))
    }

    override fun advertise(address: String) {
        advertised.addIfAbsent(address)
    }

    /**
     * String do provider record (C2 dual-homed): `idHex@extra1|extra2|…|proprio`. Os extras
     * (onion) vêm PRIMEIRO — o consumidor tenta na ordem: o anônimo casa o onion; o clearnet
     * falha rápido no onion (UnknownHost) e cai no IP próprio. Sem extras = só o próprio (C1).
     */
    private fun providerString(): String {
        val me = checkNotNull(self)
        val addrs = advertised + listOf("${me.host}:${me.port}")
        return "${me.idHex}@" + addrs.joinToString("|")
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
        RpcTypes.PUSH -> {
            // poc-05 (D1): recepção de push. A conexão JÁ está autenticada pelo Noise (o
            // handshake do servidor provou a identidade do canal). A política de aceitação
            // é a mesma dos dois backends ([PushPolicy]): grava só se o manifesto for
            // assinado por uma editora aceita — VALIDADO ANTES de gravar qualquer byte.
            val req = RpcCodec.decode(PushRequest.serializer(), body)
            val s = checkNotNull(store) { "serve() antes de aceitar push" }
            val accepted = acceptedPublishers.any { PushPolicy.accepts(req.manifestBlock, it) }
            if (!accepted) {
                // rejeição ANTES de gravar — funcionalidade não compra a aceitação
                println("PUSH REJEITADO obra=${req.obraId} (editora não aceita / assinatura inválida)")
                throw RpcException("push rejeitado: manifesto de chave não aceita")
            }
            // grava manifesto + blocos (blocos endereçados pelo hash RECOMPUTADO, nunca
            // pelo id alegado pelo remetente — o receptor não confia no rótulo do push)
            s.putManifest(ObraId(req.obraId), req.manifestBlock)
            req.blocks.forEach { s.putBlock(Block.of(it.bytes)) }
            println("PUSH ACEITO obra=${req.obraId} blocos=${req.blocks.size}")
            RpcTypes.PUSH to RpcCodec.encode(Ack.serializer(), Ack())
        }
        else -> throw RpcException("operação desconhecida: $type")
    }

    /** Rodada de malha: rediscar membros conhecidos (saída), HELLO + PEX + re-anúncios. */
    private fun meshRound() {
        val me = self ?: return
        val now = System.currentTimeMillis()
        providers.values.forEach { m -> m.entries.removeIf { it.value <= now } }
        // o próprio anúncio local também é renovado a cada rodada (republish)
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
            TcpClient.dial(peer.host, peer.port), keys, peer.publicKey,
        )
        return RpcPeer(secure)
    }
}
