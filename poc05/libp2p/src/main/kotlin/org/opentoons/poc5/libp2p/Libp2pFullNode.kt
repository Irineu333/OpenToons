package org.opentoons.poc5.libp2p

import kotlinx.coroutines.runBlocking
import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.Block
import org.opentoons.poc5.api.Blockstore
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.FullNode
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.NodeKeys
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.P2pException
import org.opentoons.poc5.api.PushPolicy
import org.opentoons.poc5.api.encodeHex
import uniffi.facade.BlockstoreCallback
import uniffi.facade.FacadeException
import uniffi.facade.ServerConfig
import uniffi.facade.ServerNode

/**
 * PoC poc-04 — full node RUST-LIBP2P atrás do seam `FullNode` (E2, a Q1): o facade
 * estendido (listen TCP+QUIC + Kademlia Mode::Server + start_providing + serve-blocks)
 * dirigido pela interface Kotlin. O conteúdo servido vem do [Blockstore] NEUTRO via
 * callback FFI — o servidor rust chama de volta a JVM a cada request (é o que torna o
 * dual-stack do E5 e a adulteração neutra do TCK possíveis). Expiry/republish são os
 * provider records do Kademlia, configurados pelo [AnnounceTuning] — internos ao backend.
 */
internal class Libp2pFullNode(
    private val keys: NodeKeys,
    private val tuning: AnnounceTuning,
) : FullNode {

    override val idHex: String get() = keys.idHex

    @Volatile private var store: Blockstore? = null
    @Volatile private var node: ServerNode? = null
    private val pendingAnnounces = linkedSetOf<String>()
    private val acceptedPublishers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // poc-05 (C2 dual-homed): endereço onion extra a anunciar (passado ao facade no start)
    @Volatile private var onionAddr: String? = null

    override val boundPort: Int
        get() = node?.listenPort()?.toInt() ?: error("start() ainda não foi chamado")

    override fun start(listen: ListenSpec, bootstrap: List<BootstrapAddr>) {
        check(node == null) { "start() já chamado" }
        // o callback lê o store ATUAL a cada request — leitura ao vivo, nunca cópia
        val callback = object : BlockstoreCallback {
            override fun manifest(obraId: String): ByteArray? = store?.manifest(ObraId(obraId))
            override fun block(cid: String): ByteArray? = store?.block(ContentId(cid))
            // poc-05 (D1): recepção de push. A MESMA PushPolicy neutra dos dois backends —
            // aceita só editora conhecida, valida a assinatura ANTES de gravar. O rust já
            // autenticou o canal (Noise); aqui decidimos o conteúdo e gravamos no store neutro.
            override fun acceptPush(obraId: String, manifest: ByteArray, blocks: List<ByteArray>): Boolean {
                val s = store ?: return false
                val ok = acceptedPublishers.any { PushPolicy.accepts(manifest, it) }
                if (!ok) return false
                s.putManifest(ObraId(obraId), manifest)
                blocks.forEach { s.putBlock(Block.of(it)) }
                return true
            }
        }
        val n = ffi("start") {
            ServerNode(
                ServerConfig(
                    identitySeedHex = keys.privateSeed.encodeHex(),
                    listenPort = listen.port.toUShort(),
                    publicIp = listen.publicHost,
                    publicPort = listen.publicPort?.toUShort(),
                    onionHost = onionAddr?.substringBeforeLast(":"), // só o host; a porta = publicPort
                    ttlMs = tuning.ttlMillis.toULong(),
                    republishMs = tuning.republishMillis.toULong(),
                ),
                callback,
            )
        }
        node = n
        ffi("bootstrap") { runBlocking { bootstrap.forEach { n.bootstrap(multiaddrOf(it)) } } }
        val queued = synchronized(pendingAnnounces) { pendingAnnounces.toList().also { pendingAnnounces.clear() } }
        queued.forEach { obra -> ffi("announce") { runBlocking { n.startProviding(obra) } } }
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
        val n = node
        if (n == null) {
            synchronized(pendingAnnounces) { pendingAnnounces.add(obra.value) }
        } else {
            ffi("announce") { runBlocking { n.startProviding(obra.value) } }
        }
    }

    override fun acceptPushes(publisherKeyHex: String) {
        acceptedPublishers.add(publisherKeyHex.lowercase())
    }

    override fun advertise(address: String) {
        // address = "host:port" (onion); vira /dns/<host>/tcp/<port> anunciado pelo facade.
        onionAddr = address
    }

    override fun selfProvider(): org.opentoons.poc5.api.Provider {
        val port = boundPort
        val peerId = uniffi.facade.peerIdFromEd25519(keys.idHex)
        return org.opentoons.poc5.api.Provider(peerId, listOf("/ip4/127.0.0.1/tcp/$port/p2p/$peerId"))
    }

    override fun stop() {
        node?.close() // dropa o ServerNode rust → runtime encerra o ator do swarm
        node = null
    }

    private inline fun <T> ffi(op: String, body: () -> T): T = try {
        body()
    } catch (e: FacadeException) {
        throw P2pException("$op: ${e.message}", e)
    }
}
