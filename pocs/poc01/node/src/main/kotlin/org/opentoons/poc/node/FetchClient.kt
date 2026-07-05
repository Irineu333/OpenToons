package org.opentoons.poc.node

import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.ipfs.multihash.Multihash
import io.libp2p.core.multiformats.Multiaddr
import org.peergos.HostBuilder
import org.peergos.Want
import org.peergos.blockstore.RamBlockstore
import org.peergos.protocol.bitswap.Bitswap
import org.peergos.protocol.bitswap.BitswapEngine
import org.peergos.protocol.dht.Kademlia
import org.peergos.protocol.dht.KademliaEngine
import org.peergos.protocol.dht.RamProviderStore
import org.peergos.protocol.dht.RamRecordStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Tarefa 3.3 — segundo nó que disca o endereço do E1 e baixa o bloco anunciado.
 * Uso: FetchClientKt <multiaddrE1> <cid>
 */
fun main(args: Array<String>) {
    val e1 = args[0]
    val cid = Cid.decode(args[1])

    val builder = HostBuilder()
        .generateIdentity()
        .listen(listOf(MultiAddress("/ip4/127.0.0.1/tcp/0")))
    val ourPeerId = Multihash.deserialize(builder.peerId.bytes)
    val engine = KademliaEngine(ourPeerId, RamProviderStore(100), RamRecordStore(), RamBlockstore())
    val dht = Kademlia(engine, false)
    builder.addProtocol(dht)
    val bitswap = Bitswap(
        BitswapEngine(RamBlockstore(), { _, _, _ -> CompletableFuture.completedFuture(false) }, Bitswap.MAX_MESSAGE_SIZE, true),
    )
    builder.addProtocol(bitswap)

    val host = builder.build()
    host.start().join()
    dht.setAddressBook(host.addressBook)

    val maddr = Multiaddr.fromString(e1)
    host.addressBook.setAddrs(maddr.getPeerId()!!, 0, maddr)
    dht.dial(host, maddr).controller.join()
    println("== FETCH: dial OK em $e1")

    val block = bitswap.get(listOf(Want(cid)), host, setOf(maddr.getPeerId()!!), false)
        .single().orTimeout(30, TimeUnit.SECONDS).join()
    println("== FETCH: bloco recebido (${block.block.size} bytes): ${String(block.block, Charsets.UTF_8)}")

    host.stop()
}
