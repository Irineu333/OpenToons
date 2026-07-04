package org.opentoons.poc5.android

import android.app.Activity
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import org.opentoons.poc5.api.BootstrapAddr
import org.opentoons.poc5.api.ChapterVerifier
import org.opentoons.poc5.api.ContentId
import org.opentoons.poc5.api.ManifestCodec
import org.opentoons.poc5.api.ObraId
import org.opentoons.poc5.api.P2pBackend
import kotlin.concurrent.thread

/**
 * PoC poc-04 — o MESMO app nas 8 células da matriz E2E (D6). Este arquivo consome
 * EXCLUSIVAMENTE o seam (`:api`): zero conceito de backend, zero branch — o backend entra
 * pelo [BackendProvider], um objeto com o mesmo nome fornecido por CADA build variant
 * (src/trama e src/libp2p). Trocar de backend = trocar a variant no build; 0 linha aqui.
 *
 * Modos (Intent extras, como no poc-02/03):
 *   adb shell am start -n org.opentoons.poc5.android.<variant>/org.opentoons.poc5.android.MainActivity \
 *     -e mode fetch -e bootstrap host:porta:pubkeyhex -e obra <obraId> -e publisher <pubkeyhex> \
 *     [-e tamperBootstrap host:porta:key] [-e wrongkeyBootstrap host:porta:key]
 *   -e mode lat -e bootstrap host:porta:pubkeyhex   (sanidade de latências, task 7.5)
 */
class MainActivity : Activity() {

    private lateinit var text: TextView

    private fun log(msg: String) {
        android.util.Log.i("poc5", msg)
        runOnUiThread { text.append(msg + "\n") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this)
        setContentView(ScrollView(this).apply { addView(text) })
        log("poc5 backend=${BackendProvider.NAME}")

        when (intent.getStringExtra("mode")) {
            "fetch" -> thread { report { runFetch() } }
            "lat" -> thread { report { runLatencies() } }
            "session" -> thread { report { runSession() } }
            "speed" -> thread { report { runSpeed() } }
            else -> log("sem mode; nada a fazer (mode=fetch|lat|session|speed)")
        }
    }

    private fun report(body: () -> Unit) = try {
        body()
    } catch (e: Throwable) {
        log("FALHA: ${e.javaClass.simpleName}: ${e.message}")
    }

    private fun bootstrapExtra(name: String): BootstrapAddr? =
        intent.getStringExtra(name)?.let {
            val (host, port, key) = it.split(":", limit = 3)
            BootstrapAddr(host, port.toInt(), key)
        }

    private val obra: ObraId get() = ObraId(intent.getStringExtra("obra") ?: "opentoons/serie-teste")
    private val publisherKey: String
        get() = intent.getStringExtra("publisher") ?: error("faltou -e publisher <pubkeyhex>")

    /** Ciclo completo: descoberta fria (só bootstrap) → download → verificação → rejeições. */
    private fun runFetch() {
        val bootstrap = bootstrapExtra("bootstrap") ?: error("faltou -e bootstrap")
        fetchCycle(bootstrap, expect = "ok")
        bootstrapExtra("tamperBootstrap")?.let { fetchCycle(it, expect = "tamper") }
        bootstrapExtra("wrongkeyBootstrap")?.let { fetchCycle(it, expect = "wrongkey") }
        log("E4 OK")
    }

    private fun fetchCycle(bootstrap: BootstrapAddr, expect: String) {
        BackendProvider.client().use { client ->
            val t0 = System.currentTimeMillis()
            client.dial(bootstrap)
            val tDial = System.currentTimeMillis() - t0

            var providers = client.resolve(obra)
            var polls = 1
            while (providers.isEmpty() && polls < 20) {
                Thread.sleep(500); providers = client.resolve(obra); polls++
            }
            check(providers.isNotEmpty()) { "nenhum provider para $obra" }
            val provider = providers.first()
            val tResolve = System.currentTimeMillis() - t0 - tDial
            log("DESCOBERTA OK provider=${provider.id.take(16)}… dial=${tDial}ms resolve=${tResolve}ms polls=$polls")

            val manifest = client.getManifest(provider, obra)
            val ids = ManifestCodec.decode(manifest).manifest.blockCids.map { ContentId(it) }
            val blocks = client.getBlocks(provider, ids)
            val tTotal = System.currentTimeMillis() - t0
            val result = ChapterVerifier(publisherKey).verify(manifest, blocks.map { it.bytes })

            when (expect) {
                "ok" -> {
                    val v = result as? ChapterVerifier.Result.Verified
                        ?: error("esperado Verified, veio $result")
                    log("ASSINATURA OK → CAPÍTULO RECONSTRUÍDO ${v.chapter.size} B em ${tTotal}ms")
                }
                "tamper" -> {
                    check(result is ChapterVerifier.Result.BlockHashMismatch) { "esperado BlockHashMismatch, veio $result" }
                    log("REJEIÇÃO OK (bloco corrompido)")
                }
                "wrongkey" -> {
                    check(result is ChapterVerifier.Result.BadSignature) { "esperado BadSignature, veio $result" }
                    log("REJEIÇÃO OK (chave errada)")
                }
            }
        }
    }

    /**
     * poc-05 — VELOCIDADE DE TRANSFERÊNCIA REAL pela clearnet: o device (dados móveis, rede
     * separada) baixa os blocos (768 KiB) de R pelo IP público N vezes, isolando o transfer
     * (getBlocks) de dial/resolve. É a medição node↔node PELA INTERNET REAL que faltava.
     */
    private fun runSpeed() {
        val bootstrap = bootstrapExtra("bootstrap") ?: error("faltou -e bootstrap")
        val n = intent.getStringExtra("n")?.toInt() ?: 5
        BackendProvider.client().use { client ->
            client.dial(bootstrap)
            var providers = client.resolve(obra); var polls = 1
            while (providers.isEmpty() && polls < 20) { Thread.sleep(500); providers = client.resolve(obra); polls++ }
            check(providers.isNotEmpty()) { "nenhum provider" }
            val provider = providers.first()
            val ids = ManifestCodec.decode(client.getManifest(provider, obra)).manifest.blockCids.map { ContentId(it) }
            val kib = ids.size * 256.0
            val times = ArrayList<Long>()
            repeat(n) { i ->
                val t0 = System.currentTimeMillis()
                val blocks = client.getBlocks(provider, ids)
                val dt = System.currentTimeMillis() - t0
                times.add(dt)
                log("SPEED[$i] ${blocks.sumOf { it.bytes.size }} B em ${dt} ms = ${"%.1f".format(kib / (dt / 1000.0))} KiB/s")
            }
            val med = times.sorted()[times.size / 2]
            log("CLEARNET REAL (dados móveis): mediana ${med} ms = ${"%.1f".format(kib / (med / 1000.0))} KiB/s")
        }
    }

    /** Sanidade de latências (task 7.5): handshake frio, reconexão, lookup. */
    private fun runLatencies() {
        val bootstrap = bootstrapExtra("bootstrap") ?: error("faltou -e bootstrap")
        repeat(5) { i ->
            BackendProvider.client().use { client ->
                val t0 = System.currentTimeMillis()
                client.dial(bootstrap)
                val cold = System.currentTimeMillis() - t0
                val t1 = System.currentTimeMillis()
                client.dial(bootstrap)
                val warm = System.currentTimeMillis() - t1
                val t2 = System.currentTimeMillis()
                client.resolve(obra)
                val lookup = System.currentTimeMillis() - t2
                log("LAT[$i] handshake=${cold}ms redial=${warm}ms lookup=${lookup}ms")
            }
        }
        log("LAT OK")
    }

    /** Sessão de robustez: N minutos de ciclos completos (dados por UID via TrafficStats). */
    private fun runSession() {
        val minutes = intent.getStringExtra("minutes")?.toLong() ?: 30
        val uid = android.os.Process.myUid()
        val rx0 = android.net.TrafficStats.getUidRxBytes(uid)
        val tx0 = android.net.TrafficStats.getUidTxBytes(uid)
        val deadline = System.currentTimeMillis() + minutes * 60_000
        var ok = 0
        var fail = 0
        while (System.currentTimeMillis() < deadline) {
            try {
                runFetchOnce()
                ok++
            } catch (e: Throwable) {
                fail++
                log("ciclo falhou: ${e.message}")
            }
            log("ciclos ok=$ok fail=$fail")
            Thread.sleep(30_000)
        }
        val rx = android.net.TrafficStats.getUidRxBytes(uid) - rx0
        val tx = android.net.TrafficStats.getUidTxBytes(uid) - tx0
        log("SESSAO ${minutes}min ok=$ok fail=$fail DADOS UID rx=${"%.2f".format(rx / 1e6)} tx=${"%.2f".format(tx / 1e6)} MB")
    }

    private fun runFetchOnce() {
        val bootstrap = bootstrapExtra("bootstrap") ?: error("faltou -e bootstrap")
        BackendProvider.client().use { client ->
            client.dial(bootstrap)
            val providers = client.resolve(obra)
            check(providers.isNotEmpty()) { "nenhum provider" }
            val manifest = client.getManifest(providers.first(), obra)
            val ids = ManifestCodec.decode(manifest).manifest.blockCids.map { ContentId(it) }
            val blocks = client.getBlocks(providers.first(), ids)
            val result = ChapterVerifier(publisherKey).verify(manifest, blocks.map { it.bytes })
            check(result is ChapterVerifier.Result.Verified) { "verificação falhou: $result" }
        }
    }
}
