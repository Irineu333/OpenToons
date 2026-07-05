package org.opentoons.poc6.android

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import org.opentoons.poc6.api.BootstrapAddr
import org.opentoons.poc6.api.ChapterVerifier
import org.opentoons.poc6.api.ContentId
import org.opentoons.poc6.api.ManifestCodec
import org.opentoons.poc6.api.ObraId
import org.opentoons.poc6.api.Provider
import org.opentoons.poc6.trama.TramaBackend
import kotlin.concurrent.thread

/**
 * poc-06 — o app leitor sobre I2P (plano A: consumidor). Consome EXCLUSIVAMENTE o seam
 * (`:api`) mais a fábrica `TramaBackend.i2pClient`; ZERO branch de transporte — o mesmo
 * `SamTransport` (Kotlin puro `java.net.Socket`) que roda no desktop fala SAM ao router I2P
 * LOCAL do device (127.0.0.1:7656). A leitura é feita A FRIO (router recém-iniciado) — o crux
 * do design (Q1). Mede warmup do túnel, tempo-até-primeiro-byte e total.
 *
 *   adb shell am start -n org.opentoons.poc6.android/.MainActivity \
 *     -e mode fetch -e rdest <destination> -e rid <idHex> [-e bdest <dest> -e bid <hex>] \
 *     [-e sam 127.0.0.1:7656]
 */
class MainActivity : Activity() {

    private lateinit var text: TextView

    private fun log(msg: String) {
        android.util.Log.i("poc6", msg)
        runOnUiThread { text.append(msg + "\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this)
        setContentView(ScrollView(this).apply { addView(text) })
        val sam = intent.getStringExtra("sam") ?: "127.0.0.1:7656"
        val (samHost, samPort) = sam.split(":").let { it[0] to it[1].toInt() }
        log("poc6 leitor I2P sam=$sam")

        when (intent.getStringExtra("mode")) {
            "fetch" -> thread { report { runFetch(samHost, samPort) } }
            "serve" -> thread { report { runServe(samHost, samPort) } }
            else -> log("sem mode (use -e mode fetch|serve)")
        }
    }

    private fun report(body: () -> Unit) = try {
        body()
    } catch (e: Throwable) {
        log("FALHA: ${e.javaClass.simpleName}: ${e.message}")
    }

    /** Leitura fria: sessão SAM no router do device → descoberta → download → verificação. */
    private fun runFetch(samHost: String, samPort: Int) {
        val rDest = intent.getStringExtra("rdest") ?: error("faltou -e rdest")
        val rId = intent.getStringExtra("rid") ?: error("faltou -e rid")
        val bDest = intent.getStringExtra("bdest") ?: rDest
        val bId = intent.getStringExtra("bid") ?: rId

        val t0 = System.currentTimeMillis()
        TramaBackend.i2pClient(samHost, samPort, nick = "poc6-android").use { client ->
            log("SESSAO SAM (router do device, frio) em ${(System.currentTimeMillis() - t0) / 1000.0}s")
            client.dial(BootstrapAddr(bDest, 0, bId))
            val provider = awaitProvider(client) ?: error("descoberta falhou")
            val tFirst = System.currentTimeMillis()
            val manifest = client.getManifest(provider, OBRA)
            val ttfb = System.currentTimeMillis() - tFirst
            val ids = ManifestCodec.decode(manifest).manifest.blockCids.map { ContentId(it) }
            val blocks = client.getBlocks(provider, ids)
            val total = System.currentTimeMillis() - t0
            val result = ChapterVerifier(PUBLISHER_KEY).verify(manifest, blocks.map { it.bytes })
            val v = result as? ChapterVerifier.Result.Verified ?: error("verificação: $result")
            log("FETCH_OK ${v.chapter.size} B verificados")
            log("MOBILE_COLD total=${total / 1000.0}s ttfb=${ttfb / 1000.0}s")
        }
    }

    /**
     * poc-06 T4 (plano B) — o MOBILE COMO NÓ PLENO QUE SERVE. Roda um full node poc06 sobre o
     * router I2P do device, publica o capítulo e o ANUNCIA a B (VPS); passa a ser discável por
     * DESTINATION mesmo atrás do CGNAT da operadora — o que o I2P destrava e o modelo clearnet
     * do ADR-0005 tornava impossível. Imprime a destination do device para o probe do DEV discar
     * e provar que o device SERVE (inbound reachability real). Mantém o nó vivo.
     */
    private fun runServe(samHost: String, samPort: Int) {
        val bDest = intent.getStringExtra("bdest") ?: error("faltou -e bdest (bootstrap B)")
        val bId = intent.getStringExtra("bid") ?: error("faltou -e bid")
        // reconstrói o MESMO capítulo determinístico do TCK (3 páginas 256 KiB, seed poc6-content)
        val pages = listOf(
            ByteArray(256 * 1024) { (it % 251).toByte() },
            ByteArray(256 * 1024) { (it % 241).toByte() },
            ByteArray(256 * 1024) { (it % 239).toByte() },
        )
        val contentKeys = org.opentoons.poc6.api.NodeKeys.fromSeed("poc6-content")
        val prep = org.opentoons.poc6.api.ChapterPublisher.prepare(contentKeys, CHAPTER_ID, SEQ, pages)

        val t0 = System.currentTimeMillis()
        val (node, session) = TramaBackend.i2pFullNode(
            "poc6-mobile-fullnode", org.opentoons.poc6.api.AnnounceTuning(),
            samHost, samPort, nick = "poc6-mobile-node",
        )
        node.serve(org.opentoons.poc6.api.MemoryBlockstore())
        node.start(org.opentoons.poc6.api.ListenSpec(0), listOf(BootstrapAddr(bDest, 0, bId)))
        node.publish(OBRA, prep.manifestBlock, prep.blocks)
        node.announce(OBRA)
        log("SESSAO SAM (nó pleno do device) em ${(System.currentTimeMillis() - t0) / 1000.0}s")
        log("MOBILE_NODE_DEST=${session.myDestination}")
        log("MOBILE_NODE_ID=${node.idHex}")
        log("nó pleno mobile SERVINDO por destination (plano B) — aguardando dials…")
        // mantém vivo; o probe do DEV disca esta destination para provar o serve
        while (true) Thread.sleep(10_000)
    }

    private fun awaitProvider(client: org.opentoons.poc6.api.P2pBackend, timeoutMs: Long = 120_000): Provider? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ps = runCatching { client.resolve(OBRA) }.getOrDefault(emptyList())
            if (ps.isNotEmpty()) return ps.first()
            Thread.sleep(1_000)
        }
        return null
    }

    private companion object {
        val OBRA = ObraId("opentoons/serie-teste")
        const val CHAPTER_ID = "opentoons/serie-teste/cap-001"
        const val SEQ = 7L
        // pubkey Ed25519 hex da editora do TCK = NodeKeys.fromSeed("poc6-content").idHex
        const val PUBLISHER_KEY = "9352f24d68a769a6a171484dccb1e2d482ef0de282c21bbf1bad7eae8939266a"
    }
}
