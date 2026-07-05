package org.opentoons.poc2.android

import android.app.Activity
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Bundle
import android.os.Process
import android.widget.ScrollView
import android.widget.TextView
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.opentoons.poc2.core.NodeIdentity
import org.opentoons.poc2.core.hexToBytes
import org.opentoons.poc2.node.ClientSession
import org.opentoons.poc2.node.FullNode
import org.opentoons.poc2.node.TestChapter
import org.opentoons.poc2.noise.NoiseChannel
import org.opentoons.poc2.rpc.ChapterService
import org.opentoons.poc2.tls.TlsChannel
import org.opentoons.poc2.tls.TlsIdentity
import org.opentoons.poc2.transport.TcpClient
import kotlin.concurrent.thread
import kotlin.system.measureNanoTime

/**
 * App da PoC poc-02 — cenários em dispositivo físico, escolhidos por Intent extras:
 *
 *   adb shell am start -n org.opentoons.poc2.android/.MainActivity \
 *     -e mode e1 -e host 192.168.x.x -e noisePort 4101 -e tlsPort 4102 \
 *     -e nodeId <idHex-do-nó> -e tlsId <idHex-do-eco-tls>          # E1: latências (2.3/2.5/3.4)
 *
 *   adb shell am start ... -e mode e4 -e bootstrap <id>@IP:4100 -e publisher <idHex>  # E4 (7.2/7.3)
 *
 *   adb shell am start ... -e mode e5 -e bootstrap <id>@IP:4100 -e minutes 30          # E5 (8.1/8.2)
 *
 * Os nós JVM sobem via poc02/net/Main.kt (ver comentário lá).
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private val clientId = NodeIdentity.generate()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { textSize = 12f }
        setContentView(ScrollView(this).apply { addView(logView) })

        val mode = intent.getStringExtra("mode") ?: "idle"
        log("poc02 — modo: $mode")
        when (mode) {
            "e1" -> thread { runE1() }
            "e4" -> thread { runE4() }
            "e5" -> thread { runE5() }
            else -> log("aguardando: use adb am start com -e mode e1|e4|e5")
        }
    }

    // ---- E1 (2.3/2.5/3.4): handshakes e reconexões nos dois canais ----

    private fun runE1() = report {
        val host = intent.getStringExtra("host") ?: error("faltou -e host")

        // Q1/Android: existe provider EdDSA de plataforma para o cert Ed25519 direto?
        runCatching { TlsIdentity.ed25519Cert(clientId) }
            .onSuccess { log("Q1/Android: cert Ed25519 direto: SUPORTADO (inesperado — registrar!)") }
            .onFailure { log("Q1/Android: cert Ed25519 direto: NÃO suportado (${it.javaClass.simpleName}) → usar ECDSA+extensão") }

        intent.getStringExtra("noisePort")?.toInt()?.let { port ->
            val nodeKey = expectedKey("nodeId")
            repeat(5) { i ->
                val nanos = measureNanoTime {
                    NoiseChannel.clientHandshake(TcpClient.dial(host, port), clientId, nodeKey).close()
                }
                log("E1b noise ${if (i == 0) "handshake" else "reconexão"} #$i: ${nanos / 1_000_000.0} ms")
            }
        }

        intent.getStringExtra("tlsPort")?.toInt()?.let { port ->
            val tlsKey = expectedKey("tlsId")
            val dialer = TlsChannel.Dialer(TlsIdentity.boundCert(clientId), tlsKey)
            repeat(5) { i ->
                val nanos = measureNanoTime { dialer.dial(host, port).first.close() }
                log("E1a tls ${if (i == 0) "handshake" else "reconexão(resumption)"} #$i: ${nanos / 1_000_000.0} ms")
            }
        }
        log("E1 OK — registrar números no relatório (limiar: <1s handshake, <500ms reconexão)")
    }

    // ---- E4 (7.2/7.3): descoberta fria → download → verificação → rejeição ----

    private fun runE4() = report {
        val bootstrap = FullNode.NodeAddress.parse(intent.getStringExtra("bootstrap") ?: error("faltou -e bootstrap"))
        val publisherKey = expectedKey("publisher")
        val client = ClientSession(clientId)

        val discovery = client.coldDiscover(bootstrap, TestChapter.OBRA_ID)
        check(discovery.providers.isNotEmpty()) { "descoberta fria falhou" }
        log("DESCOBERTA OK: ${discovery.providers.map { "${it.host}:${it.port}" }} em ${discovery.rtts} RTTs")

        val chapter: List<ByteArray>
        val nanos = measureNanoTime {
            chapter = client.fetchVerified(discovery.providers.first(), TestChapter.CHAPTER_ID, publisherKey)
        }
        log("ASSINATURA OK → CAPÍTULO RECONSTRUÍDO (${chapter.size} páginas) em ${nanos / 1_000_000} ms")

        // 7.3 — rejeição: bloco com hash divergente e manifesto com assinatura inválida
        val tampered = chapter.first().copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        check(ChapterService.cidOf(tampered) != ChapterService.cidOf(chapter.first()))
        log("REJEIÇÃO OK (bloco adulterado)")
        val wrongKey = NodeIdentity.generate().publicKey
        runCatching { client.fetchVerified(discovery.providers.first(), TestChapter.CHAPTER_ID, wrongKey) }
            .onSuccess { error("manifesto com chave errada deveria ter sido rejeitado!") }
            .onFailure { log("REJEIÇÃO OK (manifesto: ${it.javaClass.simpleName})") }
        log("E4 OK")
    }

    // ---- E5 (8.1/8.2): sessão de 30 min, lookups periódicos, tráfego por UID ----

    private fun runE5() = report {
        val bootstrap = FullNode.NodeAddress.parse(intent.getStringExtra("bootstrap") ?: error("faltou -e bootstrap"))
        val minutes = intent.getStringExtra("minutes")?.toInt() ?: 30
        val uid = Process.myUid()
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager

        val rx0 = TrafficStats.getUidRxBytes(uid)
        val tx0 = TrafficStats.getUidTxBytes(uid)
        val bat0 = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        log("E5: início — bateria=$bat0% (use dumpsys batterystats --reset antes)")

        val deadline = System.currentTimeMillis() + minutes * 60_000L
        var lookups = 0
        var failures = 0
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                val d = ClientSession(clientId).coldDiscover(bootstrap, TestChapter.OBRA_ID)
                check(d.providers.isNotEmpty())
                lookups++
                if (lookups % 10 == 0) log("… $lookups lookups (último: ${d.rtts} RTTs)")
            }.onFailure { failures++ }
            Thread.sleep(33_000)
        }

        val rx = TrafficStats.getUidRxBytes(uid) - rx0
        val tx = TrafficStats.getUidTxBytes(uid) - tx0
        val bat = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        log("E5: fim — $lookups lookups ($failures falhas) em $minutes min")
        log("tráfego UID: rx=${"%.2f".format(rx / 1048576.0)} MB tx=${"%.2f".format(tx / 1048576.0)} MB (limiar < 20 MB)")
        log("bateria: $bat0% → $bat% (limiar < 5 p.p.; conferir batterystats para o rateio)")
    }

    private fun expectedKey(extra: String): Ed25519PublicKeyParameters =
        Ed25519PublicKeyParameters((intent.getStringExtra(extra) ?: error("faltou -e $extra")).hexToBytes(), 0)

    private fun report(block: () -> Unit) {
        runCatching(block).onFailure { log("FALHA: $it") }
    }

    private fun log(msg: String) {
        android.util.Log.i("poc2", msg)
        runOnUiThread { logView.append("$msg\n") }
    }
}
