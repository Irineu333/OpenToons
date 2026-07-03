package org.opentoons.poc.node

import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.libp2p.core.PeerId
import io.libp2p.core.crypto.marshalPrivateKey
import io.libp2p.crypto.keys.generateEd25519KeyPair
import org.peergos.EmbeddedIpfs
import org.peergos.blockstore.RamBlockstore
import org.peergos.config.IdentitySection
import org.peergos.protocol.dht.RamRecordStore
import java.util.Optional
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * E5 — descoberta fria in-process: P publica, B é bootstrap, C (cliente) conhece
 * só B e o CID. Diagnóstico passo a passo do caminho provider record.
 */
class DhtColdDiscoveryTest {

    private fun node(port: Int): EmbeddedIpfs {
        val kp = generateEd25519KeyPair()
        val identity = IdentitySection(marshalPrivateKey(kp.first), PeerId.fromPubKey(kp.second))
        return EmbeddedIpfs.build(
            RamRecordStore(),
            RamBlockstore(),
            true,
            listOf(MultiAddress("/ip4/127.0.0.1/tcp/$port")),
            emptyList(),
            identity,
            emptyList(),
            { _, _, _ -> CompletableFuture.completedFuture(true) },
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
        )
    }

    private fun addr(n: EmbeddedIpfs, port: Int) =
        MultiAddress("/ip4/127.0.0.1/tcp/$port/p2p/${n.node.peerId}")

    @Test
    fun `cliente que so conhece o bootstrap descobre conteudo publicado por P`() {
        val b = node(14001); val p = node(14002); val c = node(14003)
        listOf(b, p, c).forEach { it.start() }
        try {
            // Malha de SAÍDA: B↔P se discam mutuamente (addIncomingConnection é no-op)
            val bAddr = addr(b, 14001)
            val pAddr = addr(p, 14002)
            assertTrue(p.dht.bootstrapRoutingTable(p.node, listOf(bAddr)) { true } > 0, "P→B")
            assertTrue(b.dht.bootstrapRoutingTable(b.node, listOf(pAddr)) { true } > 0, "B→P")

            // P publica um bloco e anuncia
            val cid = p.blockstore.put("capitulo de teste E5".toByteArray(), Cid.Codec.Raw).join()
            val ourAddrs = org.peergos.PeerAddresses(
                io.ipfs.multihash.Multihash.deserialize(p.node.peerId.bytes),
                listOf(io.libp2p.core.multiformats.Multiaddr.fromString("/ip4/127.0.0.1/tcp/14002")),
            )
            p.dht.provideBlock(cid, p.node, ourAddrs).join()

            // Diagnóstico 1: B tem o record? (getProviders direto no engine... via RPC do cliente)
            assertTrue(c.dht.bootstrapRoutingTable(c.node, listOf(bAddr)) { true } > 0, "C→B")
            val direct = c.dht.dial(c.node, io.libp2p.core.multiformats.Multiaddr.fromString(bAddr.toString()))
                .controller.join().getProviders(cid).join()
            println("D1 — GET_PROVIDERS direto em B: providers=${direct.providers.map { it.peerId }} closer=${direct.closerPeers.map { it.peerId to it.addresses }}")

            // Diagnóstico 1b: o que o walk de P retorna?
            val closest = p.dht.findClosestPeers(cid, 20, p.node)
            println("D1b — findClosestPeers de P: ${closest.map { it.peerId to it.addresses }}")

            // Diagnóstico 1c: provide explícito (dial com /p2p) — o átomo que funcionou antes
            val ctr = p.dht.dial(p.node, io.libp2p.core.multiformats.Multiaddr.fromString(bAddr.toString()))
                .controller.join()
            println("D1c — provide explícito: ok=${ctr.provide(cid, ourAddrs).join()}")
            Thread.sleep(500)
            val direct2 = c.dht.dial(c.node, io.libp2p.core.multiformats.Multiaddr.fromString(bAddr.toString()))
                .controller.join().getProviders(cid).join()
            println("D1c — GET_PROVIDERS após explícito: providers=${direct2.providers.map { it.peerId }}")

            // Diagnóstico 1d: provide via dial estilo dialPeer (PeerId + addr sem /p2p)
            val ctr2 = p.dht.dial(
                p.node,
                io.libp2p.core.PeerId.fromBase58(b.node.peerId.toBase58()),
                io.libp2p.core.multiformats.Multiaddr.fromString("/ip4/127.0.0.1/tcp/14001"),
            ).controller.join()
            println("D1d — provide estilo dialPeer: ok=${ctr2.provide(cid, ourAddrs).join()}")

            // Diagnóstico 1e: provideBlock de novo, com outro CID, no mesmo estado
            val cid2 = p.blockstore.put("segundo bloco E5".toByteArray(), Cid.Codec.Raw).join()
            p.dht.provideBlock(cid2, p.node, ourAddrs).join()
            Thread.sleep(500)
            val direct3 = c.dht.dial(c.node, io.libp2p.core.multiformats.Multiaddr.fromString(bAddr.toString()))
                .controller.join().getProviders(cid2).join()
            println("D1e — provideBlock(cid2) → GET_PROVIDERS em B: providers=${direct3.providers.map { it.peerId }}")

            // Diagnóstico 1f: réplica exata do provideBlock, sem engolir exceção
            val cid3 = p.blockstore.put("terceiro bloco E5".toByteArray(), Cid.Codec.Raw).join()
            val closest3 = p.dht.findClosestPeers(cid3, 20, p.node)
            println("D1f — closest=${closest3.map { it.peerId to it.addresses }}")
            for (peer in closest3) {
                val pid = io.libp2p.core.PeerId.fromBase58(peer.peerId.toBase58())
                val maddrs = peer.addresses.map { io.libp2p.core.multiformats.Multiaddr.fromString(it.toString()) }.toTypedArray()
                val fut = p.dht.dial(p.node, pid, *maddrs).controller
                    .thenCompose { it.provide(cid3, ourAddrs) }
                runCatching { fut.join() }
                    .onSuccess { println("D1f — provide→${peer.peerId} ok=$it") }
                    .onFailure { e ->
                        val root = generateSequence<Throwable>(e) { it.cause }.last()
                        println("D1f — provide→${peer.peerId} EXC ${root.javaClass.simpleName}: ${root.message}")
                    }
            }
            Thread.sleep(500)
            val direct4 = c.dht.dial(c.node, io.libp2p.core.multiformats.Multiaddr.fromString(bAddr.toString()))
                .controller.join().getProviders(cid3).join()
            println("D1f — GET_PROVIDERS(cid3) em B: providers=${direct4.providers.map { it.peerId }}")

            // Diagnóstico 1g: mesma composição, com 100ms entre controller e provide
            val cid4 = p.blockstore.put("quarto bloco E5".toByteArray(), Cid.Codec.Raw).join()
            for (peer in p.dht.findClosestPeers(cid4, 20, p.node)) {
                val pid = io.libp2p.core.PeerId.fromBase58(peer.peerId.toBase58())
                val maddrs = peer.addresses.map { io.libp2p.core.multiformats.Multiaddr.fromString(it.toString()) }.toTypedArray()
                p.dht.dial(p.node, pid, *maddrs).controller
                    .thenCompose { ctr ->
                        CompletableFuture.supplyAsync { Thread.sleep(100) }.thenCompose { ctr.provide(cid4, ourAddrs) }
                    }.join()
            }
            Thread.sleep(500)
            val direct5 = c.dht.dial(c.node, io.libp2p.core.multiformats.Multiaddr.fromString(bAddr.toString()))
                .controller.join().getProviders(cid4).join()
            println("D1g — provide com delay 100ms → GET_PROVIDERS(cid4) em B: providers=${direct5.providers.map { it.peerId }}")

            // Diagnóstico 2: walk completo do cliente
            val found = c.dht.findProviders(cid, c.node, 1).join()
            println("D2 — findProviders no cliente: ${found.map { it.peerId to it.addresses }}")

            assertTrue(found.any { it.peerId.toBase58() == p.node.peerId.toBase58() }, "cliente deve descobrir P")
        } finally {
            listOf(b, p, c).forEach { runCatching { it.stop().join() } }
        }
    }
}
