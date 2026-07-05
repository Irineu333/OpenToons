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
import java.util.Base64
import java.util.Optional

/**
 * PoC E1/E4 — nó pleno (JVM) que publica um "capítulo" de teste: blocos + manifesto
 * assinado (tarefa 6.1). Código descartável do Marco 0.
 *
 * Uso: run [portaSwarm] [duracaoSegundos] [amino|private] [ipPublico] [bootstrapAddr]
 *  - amino:    entra na DHT pública (tarefa 3.1) e anuncia provider records
 *  - private:  rede própria (E5/fallback do design D2) — sem a Amino; se
 *              [bootstrapAddr] for dado, entra na DHT própria por ele
 *  - ipPublico: endereço público manual (ADR-0006/tarefa 3.2) — anunciado via
 *               identify e incluído nos provider records
 *  - bootstrapAddr: multiaddrs (com peerId) dos peers da rede própria, separados
 *    por vírgula. IMPORTANTE: no nabu v0.8.0 conexões de ENTRADA não entram no
 *    router (addIncomingConnection é no-op e o identify reverso tem bug) —
 *    a rede precisa de malha de dials de SAÍDA, rediscada periodicamente
 */
/**
 * Workaround do bug de race do `Kademlia.provideBlock` (nabu v0.8.0): o provide
 * via `thenCompose` no future do controller escreve antes da negociação do
 * stream assentar e o ADD_PROVIDER é descartado em silêncio. Separar a espera
 * do controller (`join`) do envio resolve. Candidato a fix upstream.
 */
fun provideBlockWorkaround(ipfs: EmbeddedIpfs, cid: Cid, ourAddrs: org.peergos.PeerAddresses) {
    for (peer in ipfs.dht.findClosestPeers(cid, 20, ipfs.node)) {
        runCatching {
            val pid = PeerId.fromBase58(peer.peerId.toBase58())
            val maddrs = peer.addresses
                .map { io.libp2p.core.multiformats.Multiaddr.fromString(it.toString()) }
                .toTypedArray()
            val ctr = ipfs.dht.dial(ipfs.node, pid, *maddrs).controller
                .orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join()
            ctr.provide(cid, ourAddrs).join()
        }
    }
}

/** Identidade determinística por porta (permite montar a malha E5 a priori). */
private fun meshKey(port: Int): io.libp2p.core.crypto.PrivKey {
    val seed = java.security.MessageDigest.getInstance("SHA-256")
        .digest("opentoons-poc-e5-$port".toByteArray())
    return io.libp2p.crypto.keys.unmarshalEd25519PrivateKey(seed)
}

fun meshPeerId(port: Int): PeerId = PeerId.fromPubKey(meshKey(port).publicKey())

fun main(args: Array<String>) {
    val port = args.getOrNull(0)?.toIntOrNull() ?: 4001
    val durationSec = args.getOrNull(1)?.toLongOrNull() ?: 60
    val mode = args.getOrNull(2) ?: "amino"
    val publicIp = args.getOrNull(3)?.takeIf { it != "-" }
    val bootstrapArg = args.getOrNull(4)
    // "4001,4002,4003" (só números) = malha E5 com identidades determinísticas
    val meshPorts = bootstrapArg?.split(",")
        ?.let { parts -> parts.mapNotNull { it.toIntOrNull() }.takeIf { it.size == parts.size } }

    val privKey = if (meshPorts != null) meshKey(port) else generateEd25519KeyPair().first
    val identity = IdentitySection(
        marshalPrivateKey(privKey),
        PeerId.fromPubKey(privKey.publicKey()),
    )

    val bootstrap = when {
        meshPorts != null -> meshPorts.filter { it != port }
            .map { p -> MultiAddress("/ip4/127.0.0.1/tcp/$p/p2p/${meshPeerId(p)}") }
        mode == "private" && bootstrapArg != null -> bootstrapArg.split(",").map { MultiAddress(it) }
        mode == "private" -> emptyList()
        else -> AMINO_BOOTSTRAP
    }
    val announce = publicIp?.let { listOf(MultiAddress("/ip4/$it/tcp/$port")) } ?: emptyList()
    val ipfs = EmbeddedIpfs.build(
        RamRecordStore(),
        RamBlockstore(),
        /* provideBlocks = */ true,
        listOf(MultiAddress("/ip4/0.0.0.0/tcp/$port")),
        bootstrap,
        identity,
        announce,
        /* authoriser = */ { _, _, _ -> java.util.concurrent.CompletableFuture.completedFuture(true) },
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
    )

    ipfs.start()
    println("== E1: nó iniciado, peerId=${identity.peerId}, modo=$mode, escutando em ${ipfs.node.listenAddresses()}")

    if (mode == "amino") {
        // Anunciar com a routing table quase vazia faz o ADD_PROVIDER não alcançar
        // os peers próximos do CID — espera a tabela popular primeiro
        println("== E1: aquecendo routing table por 90s antes de anunciar…")
        Thread.sleep(90_000)
    }

    // Capítulo de teste (tarefa 6.1): blocos de conteúdo + manifesto assinado
    val signingKeys = ManifestCrypto.generateKeyPair()
    val pages = listOf(
        "OpenToons PoC (nó $port) — página 1: era uma vez um webtoon distribuído…",
        "OpenToons PoC (nó $port) — página 2: descoberto via DHT,",
        "OpenToons PoC (nó $port) — página 3: baixado por bitswap e verificado por Ed25519.",
    )
    val pageCids = pages.map { ipfs.blockstore.put(it.toByteArray(), Cid.Codec.Raw).join() }
    val manifest = Manifest("opentoons/poc/cap-$port", seq = 1, blockCids = pageCids.map { it.toString() })
    val signedManifest = ManifestCrypto.sign(manifest, signingKeys.first)
    val manifestCid = ipfs.blockstore.put(ManifestCodec.encode(signedManifest, signingKeys.second), Cid.Codec.Raw).join()

    // Bloco de teste com CID determinístico (usado pelo lookup do spike/4.1)
    val testCid = ipfs.blockstore.put("OpenToons PoC E1 — bloco de teste".toByteArray(), Cid.Codec.Raw).join()

    println("== E1: announce=${announce.ifEmpty { "(nenhum)" }}")
    println("== E1: testCid=$testCid")
    println("== E4: manifestCid=$manifestCid")
    println("== E4: pubkey=${Base64.getEncoder().encodeToString(signingKeys.second.encoded)}")
    println("== E4: blocos=${pageCids.joinToString(",")}")

    val reannounce = mode == "amino" || bootstrapArg != null
    val deadline = System.currentTimeMillis() + durationSec * 1000
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(60_000L.coerceAtMost(deadline - System.currentTimeMillis()).coerceAtLeast(1))
        if (reannounce) {
            // Redisca a malha: só dials de SAÍDA populam o router no v0.8.0
            runCatching {
                val n = ipfs.dht.bootstrapRoutingTable(ipfs.node, bootstrap) { a -> !a.contains("/wss/") }
                println("== E1: malha rediscada, $n peers de saída")
            }
            runCatching {
                // PeerAddresses.fromHost usaria os listen addrs (wildcard, indiscável);
                // com endereço público manual, o record precisa carregar o announce
                val ourPeerId = io.ipfs.multihash.Multihash.deserialize(ipfs.node.peerId.bytes)
                val ourAddrs = if (announce.isNotEmpty())
                    org.peergos.PeerAddresses(
                        ourPeerId,
                        announce.map { io.libp2p.core.multiformats.Multiaddr.fromString(it.toString()) },
                    )
                else org.peergos.PeerAddresses.fromHost(ipfs.node)
                (pageCids + manifestCid + testCid).forEach { provideBlockWorkaround(ipfs, it, ourAddrs) }
                println("== E1: provider records reanunciados (addrs=$ourAddrs)")
            }.onFailure { println("== E1: reanúncio falhou: ${it.message}") }
        }
    }
    println("== E1: conexões ativas ao final: ${ipfs.node.network.connections.size}")
    ipfs.stop().join()
    println("== E1: fim")
}
