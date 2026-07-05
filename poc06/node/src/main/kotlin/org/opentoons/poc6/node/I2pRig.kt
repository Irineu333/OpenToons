package org.opentoons.poc6.node

import org.opentoons.poc6.api.AnnounceTuning
import org.opentoons.poc6.api.BootstrapAddr
import org.opentoons.poc6.api.ChapterVerifier
import org.opentoons.poc6.api.ContentId
import org.opentoons.poc6.api.ManifestCodec
import org.opentoons.poc6.api.MemoryBlockstore
import org.opentoons.poc6.api.P2pBackend
import org.opentoons.poc6.api.Provider
import org.opentoons.poc6.api.tck.TckVectors
import org.opentoons.poc6.trama.TramaBackend
import org.opentoons.poc6.trama.wire.SamSession

/**
 * poc-06 — utilidades comuns do rig I2P. Um router i2pd local expõe SAM v3 em 127.0.0.1:7656;
 * cada nó abre uma sessão SAM (session = destination) e disca/serve por destination — o
 * "endereço deixa de ser IP:porta" do D0, exercido em código real.
 */
object I2pRig {
    const val SAM_HOST = "127.0.0.1"
    const val SAM_PORT = 7656

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    fun samHost() = env("SAM_HOST", SAM_HOST)
    fun samPort() = env("SAM_PORT", SAM_PORT.toString()).toInt()

    fun now() = System.currentTimeMillis()

    fun secs(ms: Long) = "%.1f".format(ms / 1000.0)
}

/**
 * poc-06 T0/portão — TCK sobre STREAMS I2P REAIS (spec `i2p-transport-instrument`: "Bitswap e
 * descoberta reais sobre o stream I2P"). Roda o ciclo completo do produto — R aceita push,
 * publicador anônimo EMPURRA por destination, leitor anônimo descobre via B e baixa/verifica,
 * rejeição de chave errada — tudo por dentro de túneis I2P, num único router local (host único,
 * o cenário controlado do portão D3). Prova a CORREÇÃO do instrumento sobre I2P antes de medir
 * a campanha multi-máquina. Imprime TCK_I2P_OK se todos os cenários passam.
 */
object I2pTckRigMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val t0 = I2pRig.now()
        val tuning = AnnounceTuning(ttlMillis = 60_000, republishMillis = 5_000)
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] subindo bootstrap B sobre I2P…")
        val (b, bSession) = TramaBackend.i2pFullNode(
            "poc6-tck-bootstrap", tuning, I2pRig.samHost(), I2pRig.samPort(), nick = "poc6-tck-b",
        )
        b.serve(MemoryBlockstore())
        b.start(org.opentoons.poc6.api.ListenSpec(0), emptyList())
        val bDest = bSession.myDestination
        val bAddr = BootstrapAddr(bDest, 0, b.idHex)
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] B pronto dest=${bDest.take(24)}… (${bDest.length} chars)")

        println("[${I2pRig.secs(I2pRig.now() - t0)}s] subindo replicador R sobre I2P…")
        val (r, _) = TramaBackend.i2pFullNode(
            "poc6-tck-replicador", tuning, I2pRig.samHost(), I2pRig.samPort(), nick = "poc6-tck-r",
        )
        r.serve(MemoryBlockstore())
        r.acceptPushes(TckVectors.contentKeys.idHex)
        r.start(org.opentoons.poc6.api.ListenSpec(0), listOf(bAddr))
        r.announce(TckVectors.OBRA)
        val rProvider = r.selfProvider()
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] R pronto dest=${rProvider.addresses.first().take(24)}…")

        // 1) PUSH: publicador anônimo (client transiente, só-saída) empurra por destination
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] publicador anônimo → PUSH por I2P…")
        val publisher = TramaBackend.i2pClient(I2pRig.samHost(), I2pRig.samPort(), nick = "poc6-tck-pub")
        val prep = TckVectors.prepared()
        val tPush0 = I2pRig.now()
        publisher.push(rProvider, TckVectors.OBRA, prep.manifestBlock, prep.blocks)
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] PUSH aceito em ${I2pRig.secs(I2pRig.now() - tPush0)}s")
        publisher.close()

        // 2) DESCOBERTA + FETCH: leitor anônimo conhece só B, descobre R, baixa e verifica
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] leitor anônimo → descoberta via B + fetch…")
        val reader = TramaBackend.i2pClient(I2pRig.samHost(), I2pRig.samPort(), nick = "poc6-tck-rd")
        reader.dial(bAddr)
        val provider = awaitProvider(reader)
            ?: error("TCK FALHOU: leitor não descobriu R via B sobre I2P")
        val result = fetchAndVerify(reader, provider)
        val verified = result as? ChapterVerifier.Result.Verified
            ?: error("TCK FALHOU: verificação do capítulo: $result")
        check(verified.chapter.size == 768 * 1024) { "tamanho inesperado: ${verified.chapter.size}" }
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] FETCH OK ${verified.chapter.size} B verificados (sig Ed25519 + hashes)")
        reader.close()

        // 3) REJEIÇÃO: push de chave errada é recusado ANTES de gravar (PushPolicy sobre I2P)
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] impostor → PUSH de chave errada (deve rejeitar)…")
        val impostor = TramaBackend.i2pClient(I2pRig.samHost(), I2pRig.samPort(), nick = "poc6-tck-imp")
        val forged = TckVectors.preparedWrongKey()
        val obra2 = org.opentoons.poc6.api.ObraId("opentoons/serie-teste-forjada")
        val rejected = runCatching {
            impostor.push(rProvider, obra2, forged.manifestBlock, forged.blocks)
        }.isFailure
        check(rejected) { "TCK FALHOU: push de chave errada NÃO foi rejeitado" }
        println("[${I2pRig.secs(I2pRig.now() - t0)}s] REJEIÇÃO OK (impostor recusado pela PushPolicy sobre I2P)")
        impostor.close()

        r.stop(); b.stop()
        println("TCK_I2P_OK total=${I2pRig.secs(I2pRig.now() - t0)}s — instrumento correto sobre streams I2P reais")
    }

    private fun awaitProvider(client: P2pBackend, timeoutMs: Long = 90_000): Provider? {
        val deadline = I2pRig.now() + timeoutMs
        while (I2pRig.now() < deadline) {
            val ps = runCatching { client.resolve(TckVectors.OBRA) }.getOrDefault(emptyList())
            if (ps.isNotEmpty()) return ps.first()
            Thread.sleep(1_000)
        }
        return null
    }

    private fun fetchAndVerify(client: P2pBackend, provider: Provider): ChapterVerifier.Result {
        val manifestBlock = client.getManifest(provider, TckVectors.OBRA)
        val decoded = ManifestCodec.decode(manifestBlock)
        val blocks = client.getBlocks(provider, decoded.manifest.blockCids.map { ContentId(it) })
        return ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifestBlock, blocks.map { it.bytes })
    }
}
