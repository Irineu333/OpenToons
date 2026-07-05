package org.opentoons.poc3.androidgo

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc3.core.ChapterVerifier
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

/**
 * App da PoC poc-03 (variante **go-libp2p via gomobile**) — carrega o `.aar` cross-compilado
 * e inicializa um nó libp2p no dispositivo físico sem crash (E1a, tarefa 2.3).
 *
 *   adb shell am start -n org.opentoons.poc3.androidgo/.GoActivity -e mode init
 *
 * A API é a gerada pelo gomobile: `facade.Facade.newClientNode(...)` → `facade.Node` com
 * `peerID()/dial/resolve/getBlocks/close`. Mesma superfície da variante rust (paridade D2/D4);
 * as chamadas do gomobile são bloqueantes (não `suspend`), rodadas numa thread de fundo.
 */
class GoActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { textSize = 12f }
        setContentView(ScrollView(this).apply { addView(logView) })
        val mode = intent.getStringExtra("mode") ?: "init"
        log("poc03/go — modo: $mode")
        when (mode) {
            "e2" -> thread { runE2() }
            else -> thread { runInit() }
        }
    }

    /** E2 (5.1/5.2/5.5, variante go): dial direto → download por stream → verificação Kotlin (D7). */
    private fun runE2() {
        runCatching {
            val provider = intent.getStringExtra("provider") ?: error("faltou -e provider")
            val obra = intent.getStringExtra("obra") ?: error("faltou -e obra")
            val pubKey = Ed25519PublicKeyParameters(
                (intent.getStringExtra("publisher") ?: error("faltou -e publisher")).hexToBytes(), 0,
            )
            val node = facade.Facade.newClientNode("")
            log("nó go local peerId=${node.peerID()}")
            val dialMs = measureNanoTime { node.dial(provider) } / 1_000_000
            log("DIAL OK — handshake ${dialMs} ms")
            val raw: ByteArray
            val dlNs = measureNanoTime { raw = node.getBlocks(provider, obra) }
            val all = ChapterVerifier.sliceLengthPrefixed(raw)
            val content = reLengthPrefix(all.drop(1))
            when (val r = ChapterVerifier(pubKey).verify(all.first(), content)) {
                is ChapterVerifier.Result.Verified -> {
                    val mbps = raw.size.toDouble() / (dlNs / 1e9) / 1_048_576
                    log("VERIFICADO ✓ capítulo ${r.chapter.size} B (${all.size} blocos) em ${dlNs / 1_000_000} ms — ${"%.2f".format(mbps)} MB/s")
                }
                else -> log("REJEITADO: $r")
            }
            node.close()
            log("E2 OK")
        }.onFailure { log("FALHA: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun reLengthPrefix(blocks: List<ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        blocks.forEach {
            out.write(byteArrayOf((it.size ushr 24).toByte(), (it.size ushr 16).toByte(), (it.size ushr 8).toByte(), it.size.toByte()))
            out.write(it)
        }
        return out.toByteArray()
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** E1a (2.3): o `.aar` carrega e o nó go-libp2p inicializa sem crash. */
    private fun runInit() {
        runCatching {
            val node: facade.Node
            val nanos = measureNanoTime { node = facade.Facade.newClientNode("") }
            log("NÓ GO INICIALIZADO SEM CRASH — peerId=${node.peerID()} (init ${nanos / 1_000_000} ms)")
            node.close()
            log("E1a OK — binding go-libp2p carregado e nó fechado no dispositivo")
        }.onFailure { log("FALHA: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun log(msg: String) {
        android.util.Log.i("poc3go", msg)
        runOnUiThread { logView.append("$msg\n") }
    }
}
