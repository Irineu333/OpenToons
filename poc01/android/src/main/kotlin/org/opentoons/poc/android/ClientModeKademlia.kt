package org.opentoons.poc.android

import io.libp2p.core.Stream
import io.libp2p.core.multistream.StrictProtocolBinding
import org.peergos.protocol.dht.Kademlia
import org.peergos.protocol.dht.KademliaController
import org.peergos.protocol.dht.KademliaEngine
import org.peergos.protocol.dht.KademliaProtocol
import java.util.concurrent.CompletableFuture

/**
 * DHT client puro (ADR-0005): binding do `/ipfs/kad/1.0.0` que herda o INICIADOR
 * real do nabu, mas RECUSA qualquer stream de entrada — nunca serve a DHT.
 *
 * Necessário porque o `HostImpl.newStream` do jvm-libp2p exige o protocolo
 * registrado localmente até para dial de saída (NoSuchLocalProtocolException),
 * então "simplesmente não registrar o Kademlia" não funciona.
 */
class ClientModeKademliaProtocol(engine: KademliaEngine) : KademliaProtocol(engine) {
    override fun onStartResponder(stream: Stream): CompletableFuture<KademliaController> {
        stream.close()
        return CompletableFuture.failedFuture(IllegalStateException("DHT client puro: entrada recusada"))
    }
}

class ClientModeKademliaBinding(engine: KademliaEngine) :
    StrictProtocolBinding<KademliaController>(Kademlia.WAN_DHT_ID, ClientModeKademliaProtocol(engine))
