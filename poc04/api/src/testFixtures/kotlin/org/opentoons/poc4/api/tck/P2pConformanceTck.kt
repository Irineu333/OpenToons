package org.opentoons.poc4.api.tck

import org.junit.After
import org.junit.Test
import org.opentoons.poc4.api.AnnounceTuning
import org.opentoons.poc4.api.BootstrapAddr
import org.opentoons.poc4.api.ChapterVerifier
import org.opentoons.poc4.api.FullNode
import org.opentoons.poc4.api.ListenSpec
import org.opentoons.poc4.api.MemoryBlockstore
import org.opentoons.poc4.api.P2pBackend
import org.opentoons.poc4.api.Provider
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * PoC poc-04 — a SUÍTE DE CONFORMIDADE (TCK, design D4), escrita ANTES de qualquer adapter
 * existir. Roda IDÊNTICA em qualquer backend: os mesmos vetores ([TckVectors]), os mesmos
 * cenários, as mesmas asserções. Este arquivo referencia SOMENTE o `:api` — zero conceito
 * de backend, zero branch (`if backend is …`): a implementação entra exclusivamente pelo
 * [BackendHarness] que cada módulo de adapter fornece.
 *
 * Cenários (contrato do E0): resolve (descoberta transitiva), download, verificação,
 * rejeição de adulterado (bloco corrompido; chave errada), expiry e republish.
 */
interface BackendHarness {
    /** Full node do backend com identidade determinística por semente. */
    fun newFullNode(identitySeed: String, tuning: AnnounceTuning): FullNode

    /** Client do backend (identidade efêmera, só conexões de saída). */
    fun newClient(): P2pBackend
}

abstract class P2pConformanceTck {

    protected abstract fun harness(): BackendHarness

    /** TTLs curtos p/ expiry/republish determinísticos SEM falsear o mecanismo real (D4). */
    private val tuning = AnnounceTuning(ttlMillis = 4_000, republishMillis = 1_000)

    private val opened = mutableListOf<AutoCloseable>()

    private fun <T : AutoCloseable> track(x: T): T = x.also { opened += it }

    @After
    fun tearDown() {
        opened.reversed().forEach { runCatching { it.close() } }
        opened.clear()
    }

    // ---- topologia padrão: bootstrap A + publicador P (o client só conhece A) ----

    private fun bootstrapAddrOf(node: FullNode): BootstrapAddr =
        BootstrapAddr("127.0.0.1", node.boundPort, node.idHex)

    private data class Topo(val a: FullNode, val p: FullNode, val client: P2pBackend, val aAddr: BootstrapAddr)

    private fun standardTopology(
        store: org.opentoons.poc4.api.Blockstore = MemoryBlockstore(),
        publication: org.opentoons.poc4.api.ChapterPublisher.Prepared = TckVectors.prepared(),
    ): Topo {
        val h = harness()
        val a = track(h.newFullNode("tck-bootstrap", tuning))
        a.serve(MemoryBlockstore())
        a.start(ListenSpec(0), emptyList())
        val aAddr = bootstrapAddrOf(a)

        val p = track(h.newFullNode("tck-publisher", tuning))
        p.serve(store)
        p.start(ListenSpec(0), listOf(aAddr))
        p.publish(TckVectors.OBRA, publication.manifestBlock, publication.blocks)
        p.announce(TckVectors.OBRA)

        val client = track(h.newClient())
        client.dial(aAddr)
        return Topo(a, p, client, aAddr)
    }

    private fun awaitProviders(client: P2pBackend, timeoutMs: Long = 15_000): List<Provider> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val providers = runCatching { client.resolve(TckVectors.OBRA) }.getOrDefault(emptyList())
            if (providers.isNotEmpty()) return providers
            Thread.sleep(250)
        }
        return emptyList()
    }

    private fun awaitNoProviders(client: P2pBackend, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val providers = runCatching { client.resolve(TckVectors.OBRA) }.getOrDefault(emptyList())
            if (providers.isEmpty()) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun fetchAndVerify(client: P2pBackend, provider: Provider): ChapterVerifier.Result {
        val manifestBlock = client.getManifest(provider, TckVectors.OBRA)
        val decoded = org.opentoons.poc4.api.ManifestCodec.decode(manifestBlock)
        val blocks = client.getBlocks(
            provider,
            decoded.manifest.blockCids.map { org.opentoons.poc4.api.ContentId(it) },
        )
        return ChapterVerifier(TckVectors.contentKeys.idHex).verify(manifestBlock, blocks.map { it.bytes })
    }

    // ---- cenários ----

    /** Resolve transitivo (o client só conhece A; P nunca informado) + download + verificação. */
    @Test
    fun `resolve descobre provider e download verifica integro`() {
        val topo = standardTopology()
        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "resolve não encontrou provider via bootstrap")

        val result = fetchAndVerify(topo.client, providers.first())
        val verified = result as? ChapterVerifier.Result.Verified
            ?: fail("verificação falhou: $result")
        assertEquals(TckVectors.CHAPTER_ID, verified.manifest.chapterId)
        assertEquals(TckVectors.SEQ, verified.manifest.seq)
        assertContentEquals(TckVectors.chapterBytes, verified.chapter)
    }

    /** Bloco corrompido no provider → BlockHashMismatch (adulteração via blockstore neutro). */
    @Test
    fun `bloco adulterado e rejeitado`() {
        val store = MemoryBlockstore()
        val topo = standardTopology(store = TamperingBlockstore(store))
        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "resolve não encontrou provider")

        val result = fetchAndVerify(topo.client, providers.first())
        assertTrue(
            result is ChapterVerifier.Result.BlockHashMismatch,
            "esperado BlockHashMismatch, veio $result",
        )
    }

    /** Manifesto assinado por chave errada → BadSignature. */
    @Test
    fun `manifesto de chave errada e rejeitado`() {
        val topo = standardTopology(publication = TckVectors.preparedWrongKey())
        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "resolve não encontrou provider")

        val result = fetchAndVerify(topo.client, providers.first())
        assertTrue(result is ChapterVerifier.Result.BadSignature, "esperado BadSignature, veio $result")
    }

    /** Morte do publicador → anúncio expira; revivê-lo → republish o traz de volta. */
    @Test
    fun `expiry apos morte e republish apos reviver`() {
        val store = MemoryBlockstore()
        val topo = standardTopology(store = store)
        assertTrue(awaitProviders(topo.client).isNotEmpty(), "provider não apareceu")

        // morte do publicador: sem republish, o TTL curto expira o anúncio
        val pPort = topo.p.boundPort // reviver na MESMA porta (nó real reinicia na porta configurada)
        topo.p.stop()
        assertTrue(
            awaitNoProviders(topo.client, timeoutMs = tuning.ttlMillis * 4),
            "provider não expirou após a morte do publicador",
        )

        // reviver com a MESMA identidade e o MESMO blockstore → republish reanuncia
        val revived = track(harness().newFullNode("tck-publisher", tuning))
        revived.serve(store)
        revived.start(ListenSpec(pPort), listOf(topo.aAddr))
        revived.announce(TckVectors.OBRA)

        val providers = awaitProviders(topo.client)
        assertTrue(providers.isNotEmpty(), "provider não voltou após republish")
        // como um leitor real: um dial que falha logo após o nó ressubir é re-tentado
        var result: ChapterVerifier.Result? = null
        for (attempt in 1..5) {
            result = try {
                fetchAndVerify(topo.client, awaitProviders(topo.client).first())
            } catch (e: Exception) {
                if (attempt == 5) throw e
                Thread.sleep(1_000); continue
            }
            break
        }
        assertTrue(result is ChapterVerifier.Result.Verified, "download pós-republish falhou: $result")
    }
}
