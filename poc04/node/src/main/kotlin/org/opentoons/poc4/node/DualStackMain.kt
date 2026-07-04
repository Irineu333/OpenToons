package org.opentoons.poc4.node

import org.opentoons.poc4.api.ListenSpec
import org.opentoons.poc4.api.MemoryBlockstore
import org.opentoons.poc4.api.tck.TckVectors
import org.opentoons.poc4.libp2p.Libp2pBackend
import org.opentoons.poc4.trama.TramaBackend

/**
 * PoC poc-04 E5 — full node DUAL-STACK (a ponte de migração, D7): os DOIS `FullNode`
 * sobre o MESMO blockstore, no host (peso é livre — exceção deliberada ao D1). Cliente
 * Trama e cliente libp2p baixam a mesma obra deste processo.
 * uso: DualStackMainKt --trama-listen=4200 --libp2p-listen=4201 [--public=IP] [--seed=nome]
 */
fun main(args: Array<String>) {
    val opts = NodeRunner.parseArgs(args)
    val seed = opts["seed"] ?: "poc4-dual"
    val publicIp = opts["public"]

    // UM blockstore, DUAS pilhas — o app de cada rede enxerga um full node normal
    val store = MemoryBlockstore()
    val prep = TckVectors.prepared()
    store.putManifest(TckVectors.OBRA, prep.manifestBlock)
    prep.blocks.forEach { store.putBlock(it) }

    val trama = TramaBackend.fullNode(seed)
    trama.serve(store)
    trama.start(
        ListenSpec(opts["trama-listen"]?.toInt() ?: 0, publicIp, opts["trama-listen"]?.toInt()?.takeIf { publicIp != null }),
        emptyList(),
    )
    trama.announce(TckVectors.OBRA)

    val libp2p = Libp2pBackend.fullNode(seed)
    libp2p.serve(store)
    libp2p.start(
        ListenSpec(opts["libp2p-listen"]?.toInt() ?: 0, publicIp, opts["libp2p-listen"]?.toInt()?.takeIf { publicIp != null }),
        emptyList(),
    )
    libp2p.announce(TckVectors.OBRA)

    println("DUAL-STACK UP blockstore=1 obra=${TckVectors.OBRA}")
    println("TRAMA  BOOTSTRAP_ARG=127.0.0.1:${trama.boundPort}:${trama.idHex}")
    println("LIBP2P BOOTSTRAP_ARG=127.0.0.1:${libp2p.boundPort}:${libp2p.idHex}")
    Thread.currentThread().join()
}
