package org.opentoons.poc.node

import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.ipfs.multihash.Multihash
import org.peergos.HostBuilder
import org.peergos.blockstore.RamBlockstore
import org.peergos.protocol.dht.Kademlia
import org.peergos.protocol.dht.KademliaEngine
import org.peergos.protocol.dht.RamProviderStore
import org.peergos.protocol.dht.RamRecordStore

/**
 * Diagnóstico 4.1/E5 — findProviders a partir da JVM.
 * Uso: LookupClientKt <cid> [segundosDeAquecimento] [bootstrapAddr]
 * Sem [bootstrapAddr], usa a Amino; com ele, usa a rede própria.
 */
fun main(args: Array<String>) {
    val cid = Cid.decode(args[0])
    val warmupSec = args.getOrNull(1)?.toLongOrNull() ?: 30
    val ownBootstrap = args.getOrNull(2)

    val builder = HostBuilder()
        .generateIdentity()
        .listen(listOf(MultiAddress("/ip4/0.0.0.0/tcp/0")))
    val ourPeerId = Multihash.deserialize(builder.peerId.bytes)
    val engine = KademliaEngine(ourPeerId, RamProviderStore(1000), RamRecordStore(), RamBlockstore())
    val dht = Kademlia(engine, false)
    builder.addProtocol(dht)
    val host = builder.build()
    host.start().join()
    // O walk do Kademlia usa identify em conexões novas; sem registrá-lo o
    // getCloserPeers falha com NoSuchLocalProtocolException(/ipfs/id/1.0.0)
    org.peergos.protocol.IdentifyBuilder.addIdentifyProtocol(host, emptyList())
    println("== LOOKUP: host de pé, escutando em ${host.listenAddresses()}")
    dht.setAddressBook(host.addressBook)

    // Resolução manual dos dnsaddr com log por endereço/dial
    val resolved = if (ownBootstrap != null) listOf(ownBootstrap) else AMINO_BOOTSTRAP.flatMap { a ->
        runCatching { org.peergos.protocol.dnsaddr.DnsAddr.resolve(a.toString()).toList() }
            .getOrElse { emptyList<String>() }
    }.filter { !it.contains("/wss") && !it.contains("/webtransport") && !it.contains("/p2p-circuit") }
        // jvm-libp2p não disca multiaddr /dns/ (NothingToCompleteException) —
        // resolve para /ip4/ manualmente
        .mapNotNull { addr ->
            if (!addr.startsWith("/dns/")) addr
            else runCatching {
                val host = addr.split("/")[2]
                val ip = java.net.InetAddress.getByName(host).hostAddress
                if (ip.contains(":")) null else addr.replace("/dns/$host", "/ip4/$ip")
            }.getOrNull()
        }
    println("== LOOKUP: bootstrap resolvido em ${resolved.size} endereços:")
    resolved.forEach { println("           $it") }

    var connected = 0
    for (addr in resolved) {
        try {
            val maddr = io.libp2p.core.multiformats.Multiaddr.fromString(addr)
            host.addressBook.setAddrs(maddr.getPeerId()!!, 0, maddr)
            dht.dial(host, maddr).controller.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join()
            println("== LOOKUP: dial OK $addr")
            connected++
        } catch (e: Throwable) {
            val root = generateSequence<Throwable>(e) { it.cause }.last()
            println("== LOOKUP: dial FALHOU $addr → ${root.javaClass.simpleName}: ${root.message?.take(80)}")
        }
    }
    println("== LOOKUP: bootstrap conectado a $connected nós")
    dht.bootstrap(host)
    println("== LOOKUP: aquecendo por ${warmupSec}s…")
    Thread.sleep(warmupSec * 1000)
    println("== LOOKUP: routing table tem ${engine.getKClosestPeers(cid.toBytes(), 20).size} peers próximos do alvo")

    // Diagnóstico E5: provideBlock completo (walk + dial + provide), como o nó P faz
    if (args.getOrNull(3) == "provideblock") {
        java.util.logging.Logger.getLogger("").level = java.util.logging.Level.ALL
        java.util.logging.Logger.getLogger("").handlers.forEach { it.level = java.util.logging.Level.ALL }
        val us = org.peergos.PeerAddresses(
            ourPeerId,
            listOf(io.libp2p.core.multiformats.Multiaddr.fromString("/ip4/177.203.17.5/tcp/4998")),
        )
        val closest = dht.findClosestPeers(cid, 20, host)
        println("== LOOKUP: findClosestPeers retornou ${closest.size} peers:")
        closest.forEach { println("           ${it.peerId} addrs=${it.addresses}") }
        for (p in closest) {
            try {
                val pid = io.libp2p.core.PeerId.fromBase58(p.peerId.toBase58())
                val maddrs = p.addresses.map { io.libp2p.core.multiformats.Multiaddr.fromString(it.toString()) }.toTypedArray()
                val ctr = dht.dial(host, pid, *maddrs).controller.orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join()
                val ok = ctr.provide(cid, us).join()
                println("== LOOKUP: provide→${p.peerId} ok=$ok")
            } catch (e: Throwable) {
                val root = generateSequence<Throwable>(e) { it.cause }.last()
                println("== LOOKUP: provide→${p.peerId} FALHOU: ${root.javaClass.simpleName}: ${root.message?.take(80)}")
            }
        }
        Thread.sleep(1000)
    }

    // Diagnóstico E5: provide manual no bootstrap antes do lookup
    if (args.getOrNull(3) == "provide") {
        val target = io.libp2p.core.multiformats.Multiaddr.fromString(resolved.first())
        val ctr = dht.dial(host, target).controller.join()
        val us = org.peergos.PeerAddresses(
            ourPeerId,
            listOf(io.libp2p.core.multiformats.Multiaddr.fromString("/ip4/177.203.17.5/tcp/4999")),
        )
        val ok = ctr.provide(cid, us).join()
        println("== LOOKUP: ADD_PROVIDER manual enviado (ok=$ok)")
        Thread.sleep(1000)
    }

    val t0 = System.currentTimeMillis()
    val providers = dht.findProviders(cid, host, 1).join()
    val dt = System.currentTimeMillis() - t0
    println("== LOOKUP: ${providers.size} provider(s) em ${dt}ms para $cid")
    providers.take(3).forEach { println("== LOOKUP: provider ${it.peerId} addrs=${it.addresses.take(3)}") }

    host.stop()
}
