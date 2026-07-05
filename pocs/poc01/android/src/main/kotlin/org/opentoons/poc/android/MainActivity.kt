package org.opentoons.poc.android

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import io.ipfs.cid.Cid
import io.ipfs.multiaddr.MultiAddress
import io.ipfs.multihash.Multihash
import io.libp2p.core.Host
import io.libp2p.core.PeerId
import io.libp2p.core.multiformats.Multiaddr
import org.opentoons.poc.node.ManifestCodec
import org.peergos.HostBuilder
import org.peergos.Want
import org.peergos.blockstore.RamBlockstore
import org.peergos.protocol.bitswap.Bitswap
import org.peergos.protocol.bitswap.BitswapEngine
import org.peergos.protocol.dht.Kademlia
import org.peergos.protocol.dht.KademliaEngine
import org.peergos.protocol.dht.RamProviderStore
import org.peergos.protocol.dht.RamRecordStore
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * PoC Marco 0 — app Android como DHT client puro (ADR-0005). Código descartável.
 *
 * Dois modos:
 *  - Spike E2 (padrão): conecta ao bootstrap da Amino e resolve um provider record.
 *  - E4 (extras "e1" + "manifest" + "pubkey"): rede privada — disca o nó E1,
 *    descobre o capítulo via DHT, baixa por bitswap, verifica Ed25519 e
 *    demonstra rejeição de conteúdo adulterado.
 *
 * Em ambos: nada é servido e nenhuma conexão de entrada é aceita — o binding kad
 * registrado recusa streams de entrada (ClientModeKademlia) e o bitswap nega
 * qualquer pedido de bloco (authoriser sempre-false); escuta apenas em loopback.
 */
class MainActivity : Activity() {

    private val log = StringBuilder()
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(output) })

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val e1 = intent.getStringExtra("e1")
        val bootstrap = intent.getStringExtra("bootstrap")
        val manifestCid = intent.getStringExtra("manifest")
        val pubKey = intent.getStringExtra("pubkey")
        val sessionMin = intent.getIntExtra("session_min", 0)
        thread(name = "poc-libp2p") {
            try {
                when {
                    sessionMin > 0 -> runSession(sessionMin)
                    bootstrap != null && manifestCid != null && pubKey != null -> runE5(bootstrap, manifestCid, pubKey)
                    e1 != null && manifestCid != null && pubKey != null -> runE4(e1, manifestCid, pubKey)
                    else -> runSpike()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "PoC falhou", t)
                append("ERRO: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private class ClientStack(val host: Host, val dht: Kademlia, val bitswap: Bitswap)

    /** Monta o host em modo client puro: kad recusa entrada, bitswap nunca serve. */
    private fun buildClientStack(): ClientStack {
        append("Montando host libp2p em modo client…")
        val builder = HostBuilder()
            .generateIdentity()
            .listen(listOf(MultiAddress("/ip4/127.0.0.1/tcp/0")))
        val ourPeerId = Multihash.deserialize(builder.peerId.bytes)

        val engine = KademliaEngine(ourPeerId, RamProviderStore(100), RamRecordStore(), RamBlockstore())
        // Kademlia usado só pelos helpers (bootstrap/lookup); quem fica registrado
        // no host é o binding client-mode, que recusa streams de entrada.
        val dht = Kademlia(engine, false)
        builder.addProtocol(ClientModeKademliaBinding(engine))

        // Bitswap registrado para RECEBER blocos nas conexões que nós iniciamos;
        // o authoriser sempre-false garante que nunca servimos nada a terceiros.
        val bitswap = Bitswap(
            BitswapEngine(
                RamBlockstore(),
                { _, _, _ -> CompletableFuture.completedFuture(false) },
                Bitswap.MAX_MESSAGE_SIZE,
                true,
            ),
        )
        builder.addProtocol(bitswap)

        val host = builder.build()
        host.start().join()
        // Identify é exigido pelo walk do Kademlia em conexões novas
        // (NoSuchLocalProtocolException sem ele); anuncia lista vazia — client puro
        org.peergos.protocol.IdentifyBuilder.addIdentifyProtocol(host, emptyList())
        append("Host iniciado. PeerId: ${host.peerId}")
        dht.setAddressBook(host.addressBook)
        return ClientStack(host, dht, bitswap)
    }

    private fun dial(stack: ClientStack, addr: MultiAddress): Boolean = try {
        val maddr = Multiaddr.fromString(addr.toString())
        stack.host.addressBook.setAddrs(maddr.getPeerId()!!, 0, maddr)
        stack.dht.dial(stack.host, maddr).controller.join()
        append("dial OK: $addr")
        true
    } catch (e: Throwable) {
        val root = generateSequence<Throwable>(e) { it.cause }.last()
        append("dial FALHOU $addr: ${root.javaClass.simpleName}: ${root.message}")
        Log.e(TAG, "dial $addr", e)
        false
    }

    // ---------------------------------------------------------------- E2 (spike)

    private fun runSpike() {
        val stack = buildClientStack()

        append("Conectando ao bootstrap da Amino…")
        val connected = AMINO_BOOTSTRAP.count { dial(stack, it) }
        append("Nós de bootstrap conectados: $connected")

        if (connected == 0) {
            append("SPIKE FALHOU: nenhuma conexão de bootstrap estabelecida.")
            return
        }
        append("SPIKE OK: stack inicializou e conectou a $connected nó(s) da rede.")

        val targetCid = intent.getStringExtra("lookup_cid") ?: E1_TEST_CID
        append("Resolvendo providers de $targetCid …")
        val cid = Cid.decode(targetCid)
        val providers = stack.dht.findProviders(cid, stack.host, 1).join()
        if (providers.isEmpty()) {
            append("LOOKUP: nenhum provider encontrado para o CID de teste.")
        } else {
            providers.forEach { append("LOOKUP OK: provider ${it.peerId} addrs=${it.addresses}") }
        }
    }

    // ------------------------------------------------------- E2 (sessão medida)

    /**
     * Tarefa 4.2 — sessão simulada de leitura: lookups DHT periódicos por
     * [minutes] minutos, medindo dados por UID (TrafficStats) e nível de bateria.
     * Rodar com `dumpsys battery unplug` para o nível cair de verdade no USB.
     */
    private fun runSession(minutes: Int) {
        append("SESSÃO de ${minutes}min — lookups a cada ${LOOKUP_INTERVAL_MS / 1000}s")
        val stack = buildClientStack()

        append("Conectando ao bootstrap da Amino…")
        val connected = AMINO_BOOTSTRAP.count { dial(stack, it) }
        append("Nós de bootstrap conectados: $connected")
        if (connected == 0) {
            append("SESSÃO ABORTADA: sem bootstrap.")
            return
        }

        val uid = android.os.Process.myUid()
        val rx0 = android.net.TrafficStats.getUidRxBytes(uid)
        val tx0 = android.net.TrafficStats.getUidTxBytes(uid)
        val bat0 = batteryLevel()
        append("MEDIÇÃO INÍCIO: bateria=$bat0% rx0=$rx0 tx0=$tx0 uid=$uid")

        val rnd = java.util.Random(42)
        val deadline = System.currentTimeMillis() + minutes * 60_000L
        var lookups = 0
        var found = 0
        while (System.currentTimeMillis() < deadline) {
            val digest = ByteArray(32).also { rnd.nextBytes(it) }
            val cid = Cid.buildCidV1(Cid.Codec.Raw, Multihash.Type.sha2_256, digest)
            runCatching {
                val providers = stack.dht.findProviders(cid, stack.host, 1)
                    .orTimeout(20, TimeUnit.SECONDS).join()
                if (providers.isNotEmpty()) found++
            }
            lookups++
            if (lookups % 10 == 0) {
                val rxNow = (android.net.TrafficStats.getUidRxBytes(uid) - rx0) / 1_000_000.0
                val txNow = (android.net.TrafficStats.getUidTxBytes(uid) - tx0) / 1_000_000.0
                append("progresso: $lookups lookups, rx=%.1fMB tx=%.1fMB bateria=${batteryLevel()}%%".format(rxNow, txNow))
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining > 0) Thread.sleep(LOOKUP_INTERVAL_MS.coerceAtMost(remaining))
        }

        val rxMb = (android.net.TrafficStats.getUidRxBytes(uid) - rx0) / 1_000_000.0
        val txMb = (android.net.TrafficStats.getUidTxBytes(uid) - tx0) / 1_000_000.0
        val bat1 = batteryLevel()
        append("MEDIÇÃO FIM: $lookups lookups ($found com provider), duração=${minutes}min")
        append("RESULTADO dados: rx=%.2fMB tx=%.2fMB total=%.2fMB (limiar < 20MB)".format(rxMb, txMb, rxMb + txMb))
        append("RESULTADO bateria: $bat0% → $bat1% (delta=${bat0 - bat1} p.p., limiar < 5 p.p.)")
        append("SESSÃO CONCLUÍDA")
    }

    private fun batteryLevel(): Int {
        val intent = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
    }

    // ----------------------------------------------------------------------- E4

    private fun runE4(e1Addr: String, manifestCidStr: String, pubKeyB64: String) {
        append("E4 — rede privada. Nó E1: $e1Addr")
        val stack = buildClientStack()

        if (!dial(stack, MultiAddress(e1Addr))) {
            append("E4 FALHOU: não foi possível discar o nó E1.")
            return
        }
        val e1PeerId = Multiaddr.fromString(e1Addr).getPeerId()!!

        // Descoberta via DHT (degenerada — 2 nós; desvio registrado no relatório)
        val manifestCid = Cid.decode(manifestCidStr)
        append("findProviders($manifestCidStr)…")
        val providers = stack.dht.findProviders(manifestCid, stack.host, 1).join()
        append("providers: ${providers.map { it.peerId.toString() }}")
        if (providers.none { it.peerId.toBase58() == e1PeerId.toBase58() }) {
            append("E4 FALHOU: nó E1 não apareceu como provider do manifesto.")
            return
        }
        append("DESCOBERTA OK: E1 é provider do manifesto.")
        downloadAndVerify(stack, manifestCid, pubKeyB64, e1PeerId, "E4")
    }

    // ------------------------------------------- E5 (rede própria, descoberta fria)

    /**
     * E5 — o app conhece APENAS o bootstrap da rede OpenToons e o CID do
     * manifesto; o publicador é descoberto exclusivamente pelo provider record.
     */
    private fun runE5(bootstrapAddr: String, manifestCidStr: String, pubKeyB64: String) {
        append("E5 — rede própria. Bootstrap: $bootstrapAddr (publicador DESCONHECIDO)")
        val stack = buildClientStack()

        if (!dial(stack, MultiAddress(bootstrapAddr))) {
            append("E5 FALHOU: não foi possível discar o bootstrap.")
            return
        }
        val bootstrapPeer = Multiaddr.fromString(bootstrapAddr).getPeerId()!!

        val manifestCid = Cid.decode(manifestCidStr)
        append("findProviders($manifestCidStr) via DHT própria…")
        val providers = stack.dht.findProviders(manifestCid, stack.host, 1).join()
        providers.forEach { append("provider: ${it.peerId} addrs=${it.addresses}") }

        val publisher = providers.firstOrNull { it.addresses.isNotEmpty() }
        if (publisher == null) {
            append("E5 FALHOU: nenhum provider com endereços encontrado na DHT.")
            return
        }
        if (publisher.peerId.toBase58() == bootstrapPeer.toBase58()) {
            append("E5 AVISO: o provider é o próprio bootstrap — descoberta não foi 'fria'.")
        }
        append("DESCOBERTA FRIA OK: publicador ${publisher.peerId} aprendido via provider record.")

        // Disca o publicador pelos endereços DO RECORD (nunca informados ao app)
        val publisherPeerId = PeerId.fromBase58(publisher.peerId.toBase58())
        val publisherAddrs = publisher.addresses.map {
            Multiaddr.fromString(it.toString())
        }.toTypedArray()
        stack.host.addressBook.setAddrs(publisherPeerId, 0, *publisherAddrs)
        try {
            stack.dht.dial(stack.host, publisherPeerId, *publisherAddrs).controller.join()
            append("dial OK no publicador via ${publisherAddrs.toList()}")
        } catch (e: Throwable) {
            append("E5 FALHOU: dial no publicador: ${e.message}")
            return
        }

        downloadAndVerify(stack, manifestCid, pubKeyB64, publisherPeerId, "E5")
    }

    /** Download do manifesto + blocos via bitswap (sem servir nada) e verificação. */
    private fun downloadAndVerify(stack: ClientStack, manifestCid: Cid, pubKeyB64: String, from: PeerId, tag: String) {
        val manifestBlock = fetch(stack, manifestCid, from) ?: run {
            append("$tag FALHOU: download do manifesto falhou.")
            return
        }
        val decoded = ManifestCodec.decode(manifestBlock)
        append("Manifesto: ${decoded.manifest.chapterId} seq=${decoded.manifest.seq} blocos=${decoded.manifest.blockCids.size}")

        // Verificação de assinatura + âncora de confiança (pubkey esperada)
        val expectedPk = android.util.Base64.decode(pubKeyB64, android.util.Base64.DEFAULT)
        if (!decoded.pubKeyBytes.contentEquals(expectedPk)) {
            append("$tag FALHOU: chave pública do manifesto difere da esperada.")
            return
        }
        if (!decoded.verify()) {
            append("$tag FALHOU: assinatura do manifesto inválida.")
            return
        }
        append("ASSINATURA OK (Ed25519, chave esperada).")

        val blocks = decoded.manifest.blockCids.map { cidStr ->
            val cid = Cid.decode(cidStr)
            val block = fetch(stack, cid, from) ?: run {
                append("$tag FALHOU: download do bloco $cidStr falhou.")
                return
            }
            if (!digestMatches(cid, block)) {
                append("$tag FALHOU: bloco $cidStr não corresponde ao hash do manifesto.")
                return
            }
            block
        }
        append("CAPÍTULO RECONSTRUÍDO (${blocks.size} páginas):")
        blocks.forEach { append("  · ${String(it, Charsets.UTF_8)}") }

        // 6.3 — rejeição de conteúdo adulterado
        val firstCid = Cid.decode(decoded.manifest.blockCids.first())
        val corrupted = blocks.first().copyOf()
            .also { it[0] = (it[0].toInt() xor 0x01).toByte() }
        append(
            if (!digestMatches(firstCid, corrupted)) "REJEIÇÃO OK: bloco corrompido detectado (hash ≠ CID do manifesto)."
            else "FALHA: bloco corrompido passou na verificação!",
        )

        val tamperedManifest = manifestBlock.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        val tamperedOk = runCatching { ManifestCodec.decode(tamperedManifest).verify() }.getOrDefault(false)
        append(
            if (!tamperedOk) "REJEIÇÃO OK: manifesto adulterado tem assinatura inválida."
            else "FALHA: manifesto adulterado passou na verificação!",
        )

        append("$tag OK: ciclo descoberta → download → verificação fechado.")
    }

    private fun fetch(stack: ClientStack, cid: Cid, from: PeerId): ByteArray? = try {
        stack.bitswap.get(listOf(Want(cid)), stack.host, setOf(from), false)
            .single().orTimeout(30, TimeUnit.SECONDS).join().block
    } catch (e: Throwable) {
        Log.e(TAG, "fetch $cid", e)
        null
    }

    /** Reverifica explicitamente que o bloco corresponde ao CID (sha2-256). */
    private fun digestMatches(cid: Cid, block: ByteArray): Boolean =
        MessageDigest.getInstance("SHA-256").digest(block).contentEquals(cid.hash)

    private fun append(line: String) {
        Log.i(TAG, line)
        log.append(line).append('\n')
        runOnUiThread { output.text = log.toString() }
    }

    companion object {
        private const val TAG = "OpenToonsPoC"
        private const val LOOKUP_INTERVAL_MS = 30_000L

        /** CID do bloco de teste do E1 (conteúdo fixo ⇒ CID determinístico). */
        private const val E1_TEST_CID = "bafkreictro37t3chj5kfvaqid3wjb7tgakimysaalq4s72qqy5r7dsybk4"

        // IPs resolvidos dos dnsaddr de bootstrap.libp2p.io (a resolução /dnsaddr via
        // dnsjava falhou no Android — registrado no relatório da PoC; jul/2026)
        private val AMINO_BOOTSTRAP = listOf(
            "/ip4/51.81.93.51/tcp/4001/p2p/QmQCU2EcMqAqQPR2i9bChDtGNJchTbq5TbXJJ16u19uLTa",
            "/ip4/54.38.47.166/tcp/4001/p2p/QmbLHAnMoJPWSCR5Zhtx6BHJX9KiKNN6tpvbUcqanj75Nb",
            "/ip4/147.135.44.132/tcp/4001/p2p/QmNnooDu7bfjPFoTZYxMNLWUQJyrVwtbZg5gBMjTezGAJN",
            "/ip4/15.235.144.210/tcp/4001/p2p/QmcZf59bWwK5XFi76CZX8cbJ4BhTzzA3gU1ZjYZcYW3dwt",
        ).map { MultiAddress(it) }
    }
}
