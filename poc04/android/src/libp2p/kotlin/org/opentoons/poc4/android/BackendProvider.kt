package org.opentoons.poc4.android

import org.opentoons.poc4.api.P2pBackend
import org.opentoons.poc4.libp2p.Libp2pBackend

/**
 * Composition root da build variant RUST-LIBP2P (D1): o ÚNICO arquivo desta variant, e o
 * único lugar do app que conhece o backend. O src/main nunca referencia nada além do :api.
 */
object BackendProvider {
    const val NAME = "libp2p"
    fun client(): P2pBackend = Libp2pBackend.client()
}
