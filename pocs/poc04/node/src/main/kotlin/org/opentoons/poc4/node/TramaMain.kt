package org.opentoons.poc4.node

import org.opentoons.poc4.trama.TramaBackend

/**
 * Composition root TRAMA (a "build variant" de processo, D1): o único arquivo do lado
 * Trama que referencia o backend; o driver é 100% neutro.
 * uso: TramaMainKt (node|client) [args do NodeRunner]
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "node" -> NodeRunner.runNode(TramaBackend::fullNode, args.drop(1).toTypedArray())
        "client" -> NodeRunner.runClient(TramaBackend::client, args.drop(1).toTypedArray())
        else -> error("uso: (node|client) --…")
    }
}
