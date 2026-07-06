package org.opentoons.poc7.trama

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.opentoons.poc7.api.AnnounceTuning
import org.opentoons.poc7.api.Blockstore
import org.opentoons.poc7.api.BootstrapAddr
import org.opentoons.poc7.api.ChapterPublisher
import org.opentoons.poc7.api.ChapterVerifier
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.FullNode
import org.opentoons.poc7.api.ListenSpec
import org.opentoons.poc7.api.ManifestCodec
import org.opentoons.poc7.api.MemoryBlockstore
import org.opentoons.poc7.api.P2pBackend
import org.opentoons.poc7.api.Provider
import org.opentoons.poc7.trama.wire.TcpTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * poc-07 — TCK de conformidade portado (JUnit → kotlin.test) e rodando em `commonTest`, então
 * executa IDÊNTICO no host (jvmTest) E no alvo Native (iosSimulatorArm64Test) — o PORTÃO D5.
 * Levanta full nodes + client Trama sobre `TcpTransport` (loopback) e roda os mesmos cenários
 * do poc-04: resolve transitivo, download verificado, adulteração rejeitada, push, expiry.
 * Nenhum número de campanha aqui — só correção. (O simulador só serve ao portão, D-rules.)
 */
class TramaTckTest {

    private val tuning = AnnounceTuning(ttlMillis = 4_000, republishMillis = 1_000)
    private val opened = mutableListOf<AutoCloseable>()
    private fun <T : AutoCloseable> track(x: T): T = x.also { opened += it }

    @AfterTest
    fun tearDown() {
        opened.reversed().forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun sleepMs(ms: Long) = runBlocking { delay(ms) }

    private fun fullNode(seed: String) = TramaFullNode(
        org.opentoons.poc7.api.NodeKeys.fromSeed(seed), tuning, TcpTransport(0),
    )
    private fun client(): P2pBackend = TramaClient(
        org.opentoons.poc7.api.NodeKeys.generate(), TcpTransport(0), anonymous = false,
    )

    private fun bootstrapAddrOf(node: FullNode) = BootstrapAddr("127.0.0.1", node.boundPort, node.idHex)

    private data class Topo(val a: FullNode, val p: FullNode, val client: P2pBackend, val aAddr: BootstrapAddr)

    private fun standardTopology(
        store: Blockstore = MemoryBlockstore(),
        publication: ChapterPublisher.Prepared = TckVectors.prepared(),
    ): Topo {
        val a = track(fullNode("tck-bootstrap"))
        a.serve(MemoryBlockstore())
        a.start(ListenSpec(0), emptyList())
        val aAddr = bootstrapAddrOf(a)

        val p = track(fullNode("tck-publisher"))
        p.serve(store)
        p.start(ListenSpec(0), listOf(aAddr))
        p.publish(TckVectors.OBRA, publication.manifestBlock, publication.blocks)
        p.announce(TckVectors.OBRA)

        val c = track(client())
        c.dial(aAddr)
        return Topo(a, p, c, aAddr)
    }

    private fun awaitProviders(client: P2pBackend, timeoutMs: Long = 15_000): List<Provider> {
        var waited = 0L
        while (waited < timeoutMs) {
            val providers = runCatching { client.resolve(TckVectors.OBRA) }.getOrDefault(emptyList())
            if (providers.isNotEmpty()) return providers
            sleepMs(250); waited += 250
        }
        return emptyList()
    }

    private fun awaitNoProviders(client: P2pBackend, timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            val providers = runCatching { client.resolve(TckVectors.OBRA) }.getOrDefault(emptyList())
            if (providers.isEmpty()) return true
            sleepMs(250); waited += 250
        }
        return false
    }

    private fun fetchAndVerify(client: P2pBackend, provider: Provider): ChapterVerifier.Result {
        val manifestBlock = client.getManifest(provider, TckVectors.OBRA)
        val decoded = ManifestCodec.decode(manifestBlock)
        val blocks = client.getBlocks(provider, decoded.manifest.blockCids.map { ContentId(it) })
        return ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifestBlock, blocks.map { it.bytes })
    }

    @Test
    fun resolveDescobreProviderEDownloadVerificaIntegro() {
        val topo = standardTopology()
        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "resolve não encontrou provider via bootstrap")
        val result = fetchAndVerify(topo.client, providers.first())
        val verified = result as? ChapterVerifier.Result.Verified ?: fail("verificação falhou: $result")
        assertEquals(TckVectors.CHAPTER_ID, verified.manifest.chapterId)
        assertEquals(TckVectors.SEQ, verified.manifest.seq)
        assertTrue(verified.chapter.contentEquals(TckVectors.chapterBytes), "capítulo divergente")
    }

    @Test
    fun blocoAdulteradoERejeitado() {
        val topo = standardTopology(store = TamperingBlockstore(MemoryBlockstore()))
        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "resolve não encontrou provider")
        val result = fetchAndVerify(topo.client, providers.first())
        assertTrue(result is ChapterVerifier.Result.BlockHashMismatch, "esperado BlockHashMismatch, veio $result")
    }

    @Test
    fun manifestoDeChaveErradaERejeitado() {
        val topo = standardTopology(publication = TckVectors.preparedWrongKey())
        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "resolve não encontrou provider")
        val result = fetchAndVerify(topo.client, providers.first())
        assertTrue(result is ChapterVerifier.Result.BadSignature, "esperado BadSignature, veio $result")
    }

    @Test
    fun pushAutenticadoGravadoEServido() {
        val store = MemoryBlockstore()
        val r = track(fullNode("tck-replicador"))
        r.serve(store)
        r.acceptPushes(TckVectors.contentKeys.idHex)
        r.start(ListenSpec(0), emptyList())
        val rProvider = r.selfProvider()

        val publisher = track(client())
        val prep = TckVectors.prepared()
        publisher.push(rProvider, TckVectors.OBRA, prep.manifestBlock, prep.blocks)

        val reader = track(client())
        val result = fetchAndVerify(reader, rProvider)
        val verified = result as? ChapterVerifier.Result.Verified ?: fail("push não ficou servível: $result")
        assertEquals(TckVectors.CHAPTER_ID, verified.manifest.chapterId)
        assertTrue(verified.chapter.contentEquals(TckVectors.chapterBytes))
    }

    @Test
    fun pushDeChaveErradaRejeitadoAntesDeGravar() {
        val store = MemoryBlockstore()
        val r = track(fullNode("tck-replicador"))
        r.serve(store)
        r.acceptPushes(TckVectors.contentKeys.idHex)
        r.start(ListenSpec(0), emptyList())
        val rProvider = r.selfProvider()

        val impostor = track(client())
        val forged = TckVectors.preparedWrongKey()
        val rejected = runCatching {
            impostor.push(rProvider, TckVectors.OBRA, forged.manifestBlock, forged.blocks)
        }.isFailure
        assertTrue(rejected, "push de chave errada deveria ter sido rejeitado")

        val reader = track(client())
        val absent = runCatching { reader.getManifest(rProvider, TckVectors.OBRA) }.isFailure
        assertTrue(absent, "R gravou conteúdo de chave errada — vazou a rejeição")
    }

    @Test
    fun expiryAposMorteERepublishAposReviver() {
        val store = MemoryBlockstore()
        val topo = standardTopology(store = store)
        assertTrue(awaitProviders(topo.client).isNotEmpty(), "provider não apareceu")

        val pPort = topo.p.boundPort
        topo.p.stop()
        assertTrue(
            awaitNoProviders(topo.client, timeoutMs = tuning.ttlMillis * 4),
            "provider não expirou após a morte do publicador",
        )

        val revived = track(fullNode("tck-publisher"))
        revived.serve(store)
        revived.start(ListenSpec(pPort), listOf(topo.aAddr))
        revived.announce(TckVectors.OBRA)

        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "provider não voltou após republish")
        var result: ChapterVerifier.Result? = null
        for (attempt in 1..5) {
            result = try {
                fetchAndVerify(topo.client, awaitProviders(topo.client).first())
            } catch (e: Exception) {
                if (attempt == 5) throw e
                sleepMs(1_000); continue
            }
            break
        }
        assertTrue(result is ChapterVerifier.Result.Verified, "download pós-republish falhou: $result")
    }
}
