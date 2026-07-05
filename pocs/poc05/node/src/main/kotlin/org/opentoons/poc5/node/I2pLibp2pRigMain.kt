package org.opentoons.poc5.node

import org.opentoons.poc5.api.AnnounceTuning
import org.opentoons.poc5.api.AnonymityConfig
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.ChapterVerifier
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.ListenSpec
import org.opentoons.poc5.api.ManifestCodec
import org.opentoons.poc5.api.MemoryBlockstore
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.tck.TckVectors
import org.opentoons.poc5.libp2p.Libp2pBackend

/**
 * poc-06 E-fase — libp2p SOBRE I2P (a comparação parqueada, agora que o núcleo passou).
 * Reusa o backend rust-libp2p do poc-05 SEM mudança: só troca o transporte anônimo de Tor por
 * I2P — o `SocksProxy` aponta para o proxy SOCKS do i2pd (127.0.0.1:4447, resolve `.b32.i2p`)
 * e o full node anuncia `/dns/<b32>.i2p/tcp/<port>` (o i2pd server tunnel entrega o inbound ao
 * listener TCP local). Mede a DESCOBERTA POR KADEMLIA sobre I2P — a variável que a Trama (gossip)
 * não tem, e o custo do walk multi-dial sobre túnel (o que no poc-05 foi 6× mais caro que a
 * Trama sobre Tor). NÃO extrapola: roda Kademlia real sobre I2P e cronometra.
 *
 *   args: <server-b32.i2p> <listen-port> [socks-port=4447]
 */
object I2pLibp2pRigMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val serverB32 = args.getOrNull(0) ?: error("arg1 = <server-b32.i2p> (do i2pd server tunnel)")
        val listenPort = args.getOrNull(1)?.toInt() ?: 6000
        val socksPort = args.getOrNull(2)?.toInt() ?: 4447
        val t0 = System.currentTimeMillis()
        fun secs() = "%.1f".format((System.currentTimeMillis() - t0) / 1000.0)

        // R = full node libp2p: Kademlia server, provê a obra, anuncia sua .b32.i2p
        println("[${secs()}s] subindo R (libp2p) sobre I2P, anunciando $serverB32 …")
        val r = Libp2pBackend.fullNode("poc6-libp2p-r", AnnounceTuning())
        r.serve(MemoryBlockstore())
        r.start(ListenSpec(listenPort, publicHost = serverB32, publicPort = listenPort), emptyList())
        val prep = TckVectors.prepared()
        r.publish(TckVectors.OBRA, prep.manifestBlock, prep.blocks)
        r.announce(TckVectors.OBRA)
        val rAddr = BootstrapAddr(serverB32, listenPort, r.idHex)
        println("[${secs()}s] R pronto peer=${r.idHex.take(16)}… listen=$listenPort")

        // dá tempo do leaseSet de R publicar e do listener estabilizar antes de discar
        println("[${secs()}s] aguardando leaseSet de R publicar (25s)…")
        Thread.sleep(25_000)

        // P = client libp2p via SOCKS do i2pd; conhece R; DESCOBRE a obra por Kademlia sobre I2P
        println("[${secs()}s] P (libp2p client, SOCKS i2pd:$socksPort) discando R por .b32.i2p …")
        val p = Libp2pBackend.client(AnonymityConfig.tor("127.0.0.1", socksPort))
        var dialed = false
        for (attempt in 1..6) {
            try { p.dial(rAddr); dialed = true; break }
            catch (e: Exception) {
                println("[${secs()}s] dial tentativa $attempt falhou (${e.message?.take(60)}) — retry em 8s…")
                Thread.sleep(8_000)
            }
        }
        check(dialed) { "dial de R falhou após retries (libp2p-sobre-I2P)" }
        println("[${secs()}s] dial OK; resolvendo a obra por Kademlia SOBRE I2P …")

        val tDisc = System.currentTimeMillis()
        var providers = p.resolve(TckVectors.OBRA)
        var polls = 1
        while (providers.isEmpty() && polls < 30) {
            Thread.sleep(1000); providers = p.resolve(TckVectors.OBRA); polls++
        }
        val discMs = System.currentTimeMillis() - tDisc
        check(providers.isNotEmpty()) { "DHT/Kademlia NÃO descobriu provider sobre I2P" }
        println("[${secs()}s] KAD_DISCOVER_OK descoberta por Kademlia SOBRE I2P em ${"%.1f".format(discMs / 1000.0)}s (polls=$polls)")

        // fecha o ciclo: baixa e verifica pelo mesmo caminho libp2p-sobre-I2P
        val provider = providers.first()
        val manifest = p.getManifest(provider, TckVectors.OBRA)
        val ids = ManifestCodec.decode(manifest).manifest.blockCids.map { ContentId(it) }
        val blocks = p.getBlocks(provider, ids)
        val result = ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifest, blocks.map { it.bytes })
        val v = result as? ChapterVerifier.Result.Verified ?: error("verificação: $result")
        println("[${secs()}s] FETCH_OK ${v.chapter.size} B verificados via libp2p-sobre-I2P")
        println("LIBP2P_I2P_OK kad_discover=${"%.1f".format(discMs / 1000.0)}s total=${secs()}s")
        p.close(); r.stop()
    }
}
