package org.opentoons.poc4.node

import org.opentoons.poc4.libp2p.Libp2pBackend

/**
 * Composition root RUST-LIBP2P (a "build variant" de processo, D1).
 * Exige -Djna.library.path=<dir do libuniffi_facade.dylib>.
 * uso: Libp2pMainKt (node|client) [args do NodeRunner]
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "node" -> NodeRunner.runNode(Libp2pBackend::fullNode, args.drop(1).toTypedArray())
        "client" -> NodeRunner.runClient(Libp2pBackend::client, args.drop(1).toTypedArray())
        else -> error("uso: (node|client) --…")
    }
}
