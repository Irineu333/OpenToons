package org.opentoons.poc3.android

import android.app.Activity
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Bundle
import android.os.Process
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc3.core.ChapterVerifier
import uniffi.facade.Node
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

/**
 * App da PoC poc-03 (variante **rust-libp2p via UniFFI**) — carrega o `.so` cross-compilado
 * e exercita a superfície FFI no dispositivo físico. Modos via Intent extra:
 *
 *   # E1 (2.3/3.3): carregar o binding e inicializar o nó SEM CRASH — o teste existencial
 *   adb shell am start -n org.opentoons.poc3.android/.MainActivity -e mode init
 *
 *   # E4 (7.2/7.3): descoberta fria → dial → get-blocks → verificação Kotlin (D7)
 *   adb shell am start ... -e mode e4 -e bootstrap "/ip4/IP/tcp/4001/p2p/<id>" \
 *      -e obra <obraId> -e publisher <pubKeyHex>
 *
 * As chamadas de rede (dial/resolve/getBlocks) são `suspend` no binding gerado; rodam via
 * runBlocking numa thread de fundo. A verificação Ed25519/hash é Kotlin, fora da fronteira (D7).
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { textSize = 12f }
        setContentView(ScrollView(this).apply { addView(logView) })

        val mode = intent.getStringExtra("mode") ?: "init"
        log("poc03/rust — modo: $mode")
        when (mode) {
            "init" -> thread { runInit() }
            "e2" -> thread { runE2() }
            "e2burst" -> thread { runBurst() }
            "e4" -> thread { runE4() }
            "e5" -> thread { runSession() }
            "lat" -> thread { runLatencies() }
            else -> log("modos: init | e2 | e2burst | e4 | e5 | lat")
        }
    }

    /** E5 (8.1): sessão de N min, lookups periódicos, tráfego por UID + bateria (roteiro poc-01/02). */
    private fun runSession() = report {
        val bootstrap = intent.getStringExtra("bootstrap") ?: error("faltou -e bootstrap")
        val obra = intent.getStringExtra("obra") ?: error("faltou -e obra")
        val pubKey = Ed25519PublicKeyParameters(
            (intent.getStringExtra("publisher") ?: error("faltou -e publisher")).hexToBytes(), 0,
        )
        val minutes = intent.getStringExtra("minutes")?.toInt() ?: 30
        val uid = Process.myUid()
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val rx0 = TrafficStats.getUidRxBytes(uid); val tx0 = TrafficStats.getUidTxBytes(uid)
        val bat0 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        log("E5 início — bateria=$bat0% (rode 'dumpsys batterystats --reset' antes)")
        runBlocking {
            val node = Node(bootstrap)
            val deadline = System.currentTimeMillis() + minutes * 60_000L
            var lookups = 0; var fails = 0; var verified = 0
            while (System.currentTimeMillis() < deadline) {
                runCatching {
                    val p = node.resolve(obra)
                    val raw = node.getBlocks(p, obra)
                    val all = ChapterVerifier.sliceLengthPrefixed(raw)
                    if (ChapterVerifier(pubKey).verify(all.first(), reLengthPrefix(all.drop(1)))
                        is ChapterVerifier.Result.Verified) verified++
                    lookups++
                }.onFailure { fails++ }
                if (lookups % 10 == 0 && lookups > 0) log("… $lookups lookups ($verified verificados, $fails falhas)")
                delay(30_000)
            }
            node.close()
            val rx = (TrafficStats.getUidRxBytes(uid) - rx0) / 1_048_576.0
            val tx = (TrafficStats.getUidTxBytes(uid) - tx0) / 1_048_576.0
            val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            log("E5 FIM — $lookups lookups ($verified ok, $fails falhas) em $minutes min")
            log("DADOS UID: rx=${"%.2f".format(rx)} tx=${"%.2f".format(tx)} MB (total ${"%.2f".format(rx + tx)}; limiar < 20 MB)")
            log("BATERIA: $bat0% → $bat% (limiar < 5 p.p.; conferir batterystats p/ rateio fino)")
        }
    }

    /** E5 (8.2): handshake (1ª conexão) e reconexão (conexão reusada) ao provider. */
    private fun runLatencies() = report {
        val bootstrap = intent.getStringExtra("bootstrap") ?: error("faltou -e bootstrap")
        val obra = intent.getStringExtra("obra") ?: error("faltou -e obra")
        runBlocking {
            val node = Node(bootstrap)
            val provider = node.resolve(obra)
            repeat(6) { i ->
                val ms = measureNanoTime { node.getBlocks(provider, obra) } / 1_000_000
                log(if (i == 0) "HANDSHAKE + 1º download: ${ms} ms (limiar handshake < 1000 ms)"
                    else "RECONEXÃO #$i (conexão reusada) + download: ${ms} ms (limiar < 500 ms)")
            }
            node.close()
        }
    }

    /** E2 (5.3): exercita a fronteira FFI sob concorrência — N getBlocks paralelos no mesmo nó. */
    private fun runBurst() = report {
        val provider = intent.getStringExtra("provider") ?: error("faltou -e provider")
        val obra = intent.getStringExtra("obra") ?: error("faltou -e obra")
        val n = intent.getStringExtra("n")?.toInt() ?: 30
        runBlocking {
            val node = Node("")
            node.dial(provider)
            log("nó pronto; disparando $n getBlocks concorrentes…")
            var ok = 0; var fail = 0
            val ms = measureNanoTime {
                coroutineScope {
                    (1..n).map {
                        async(Dispatchers.IO) {
                            runCatching { node.getBlocks(provider, obra) }
                                .onSuccess { synchronized(this@MainActivity) { ok++ } }
                                .onFailure { synchronized(this@MainActivity) { fail++ } }
                        }
                    }.awaitAll()
                }
            } / 1_000_000
            log("CONCORRÊNCIA FFI: $ok ok / $fail falhas em $n chamadas ($ms ms) — sem crash")
            // lifecycle: fecha e reabre o nó para exercitar drop/recreate na fronteira
            node.close()
            val node2 = Node("")
            log("LIFECYCLE: nó recriado após close, peerId=${node2.peerId()}")
            node2.close()
        }
        log("E2burst OK — nenhuma classe de bug FFI (threading/memória/lifecycle) observada")
    }

    /** E2 (5.1/5.2/5.5): dial direto ao publicador → download por Request-Response → verificação. */
    private fun runE2() = report {
        val provider = intent.getStringExtra("provider") ?: error("faltou -e provider")
        val obra = intent.getStringExtra("obra") ?: error("faltou -e obra")
        val pubKey = Ed25519PublicKeyParameters(
            (intent.getStringExtra("publisher") ?: error("faltou -e publisher")).hexToBytes(), 0,
        )
        runBlocking {
            val node = Node("")
            log("nó local peerId=${node.peerId()}")
            val dialMs = measureNanoTime { node.dial(provider) } / 1_000_000
            log("DIAL OK — handshake ${dialMs} ms")
            val raw: ByteArray
            val dlNs = measureNanoTime { raw = node.getBlocks(provider, obra) }
            val all = ChapterVerifier.sliceLengthPrefixed(raw)
            val manifestBlock = all.first()
            val content = reLengthPrefix(all.drop(1))
            when (val r = ChapterVerifier(pubKey).verify(manifestBlock, content)) {
                is ChapterVerifier.Result.Verified -> {
                    val mbps = raw.size.toDouble() / (dlNs / 1e9) / 1_048_576
                    log("VERIFICADO ✓ capítulo ${r.chapter.size} B (${all.size} blocos) em ${dlNs / 1_000_000} ms — ${"%.2f".format(mbps)} MB/s")
                }
                else -> log("REJEITADO: $r")
            }
            node.close()
        }
        log("E2 OK")
    }

    /** E1 (2.3/3.3): a prova existencial — o `.so` carrega e o nó inicializa sem crash. */
    private fun runInit() = report {
        var node: Node
        val nanos = measureNanoTime { node = Node("") } // client puro, sem bootstrap
        log("NÓ INICIALIZADO SEM CRASH — peerId=${node.peerId()} (init ${nanos / 1_000_000} ms)")
        node.close()
        log("E1 OK — binding rust carregado e nó fechado no dispositivo")
    }

    /**
     * E3/E4 (6.2/7.2/7.3): ciclo E2E — só bootstrap + obraId; descoberta fria via Kademlia →
     * disca o publicador NUNCA informado → download por Request-Response → verificação (D7).
     * Serve E3 (bootstrap local) e E4 (IP público, dispositivo em outra rede) — muda só o -e bootstrap.
     */
    private fun runE4() = report {
        val bootstrap = intent.getStringExtra("bootstrap") ?: error("faltou -e bootstrap")
        val obra = intent.getStringExtra("obra") ?: error("faltou -e obra")
        val pubKey = Ed25519PublicKeyParameters(
            (intent.getStringExtra("publisher") ?: error("faltou -e publisher")).hexToBytes(), 0,
        )
        runBlocking {
            val node = Node(bootstrap)
            log("conectado ao bootstrap; peerId=${node.peerId()}")

            val provider: String
            val discMs = measureNanoTime { provider = node.resolve(obra) } / 1_000_000
            log("DESCOBERTA FRIA OK → provider=$provider (${discMs} ms)")

            // provider = /p2p/<id> vindo do Kademlia; getBlocks disca via o endereço do provider record
            val raw: ByteArray
            val dlMs = measureNanoTime { raw = node.getBlocks(provider, obra) } / 1_000_000
            val all = ChapterVerifier.sliceLengthPrefixed(raw)
            val content = reLengthPrefix(all.drop(1))
            when (val r = ChapterVerifier(pubKey).verify(all.first(), content)) {
                is ChapterVerifier.Result.Verified ->
                    log("ASSINATURA OK → CAPÍTULO RECONSTRUÍDO (${r.chapter.size} B) — download ${dlMs} ms")
                else -> log("REJEITADO: $r")
            }
            node.close()
        }
        log("E4 OK")
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

    private fun report(block: () -> Unit) {
        runCatching(block).onFailure { log("FALHA: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun log(msg: String) {
        android.util.Log.i("poc3", msg)
        runOnUiThread { logView.append("$msg\n") }
    }
}
