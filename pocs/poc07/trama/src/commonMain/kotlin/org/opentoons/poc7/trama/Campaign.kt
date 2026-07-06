package org.opentoons.poc7.trama

import org.opentoons.poc7.api.BootstrapAddr
import org.opentoons.poc7.api.ChapterPublisher
import org.opentoons.poc7.api.ChapterVerifier
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.ManifestCodec
import org.opentoons.poc7.api.NodeKeys
import org.opentoons.poc7.api.ObraId
import org.opentoons.poc7.api.P2pBackend
import org.opentoons.poc7.api.Provider
import org.opentoons.poc7.trama.wire.TcpTransport
import kotlin.time.TimeSource

/**
 * poc-07 — vetores de CAMPANHA compartilhados por `commonMain`: o MESMO capítulo determinístico
 * (768 KiB) e as MESMAS chaves usados pelo full node (jvm, VPS) e pelo leitor (Native, iPhone).
 * Como é o mesmo código nos dois alvos, o conteúdo servido e o esperado batem por construção.
 */
object CampaignVectors {
    val OBRA = ObraId("opentoons/serie-teste")
    const val CHAPTER_ID = "opentoons/serie-teste/cap-001"
    const val SEQ = 7L

    const val CONTENT_KEY_SEED = "poc7-content"
    const val VPS_NODE_SEED = "poc7-vps"

    /**
     * Pubkeys CONHECIDAS (âncoras de confiança), como um app real embarca: o leitor confia na
     * chave do publicador e conhece a chave do bootstrap — sem derivar de semente. Valores REAIS
     * emitidos pelo nó da VPS em execução (VPS-NODE-UP/OBRA), não inventados. Isto também evita,
     * no leitor Android, o caminho "derivar pubkey da privada" (que o BouncyCastle stripped do
     * Android sombreia no provider JDK da cryptography-kotlin).
     */
    const val CONTENT_PUB_KEY_HEX = "fa49782649545fb30a9b25638913eb045d93d97c1dcbcfb3c60711450b140278"
    const val VPS_NODE_ID_HEX = "34f6df44dec8a6e34d11c6b87a1fd682539db5f013b1a5c2ea6a609884052374"

    /** Chave da editora — usada pelo PUBLICADOR/servidor (JVM) para ASSINAR. Determinística. */
    val contentKeys: NodeKeys get() = NodeKeys.fromSeed(CONTENT_KEY_SEED)

    /** idHex do bootstrap/VPS para o leitor discar (constante conhecida). */
    val vpsNodeIdHex: String get() = VPS_NODE_ID_HEX

    /** 768 KiB determinísticos (3 páginas de 256 KiB) — igual ao TCK. */
    fun pages(): List<ByteArray> = listOf(
        ByteArray(256 * 1024) { (it % 251).toByte() },
        ByteArray(256 * 1024) { (it % 241).toByte() },
        ByteArray(256 * 1024) { (it % 239).toByte() },
    )

    fun prepared(): ChapterPublisher.Prepared =
        ChapterPublisher.prepare(contentKeys, CHAPTER_ID, SEQ, pages())
}

/** Amostra de leitura E2E cronometrada — campos primitivos p/ leitura direta do Swift. */
data class ReadSample(
    val ok: Boolean,
    val detail: String,
    val connectMs: Long,
    val ttfbMs: Long,
    val downloadMs: Long,
    val verifyMs: Long,
    val totalMs: Long,
    val chapterBytes: Int,
) {
    fun line(): String =
        "POC07-READ ok=$ok connect=${connectMs}ms ttfb=${ttfbMs}ms download=${downloadMs}ms " +
            "verify=${verifyMs}ms total=${totalMs}ms bytes=$chapterBytes detail=$detail"
}

/**
 * poc-07 — o leitor E2E A FRIO, em `commonMain`: cada chamada abre um client novo (handshake
 * Noise + TCP novos, sem reuso), disca o bootstrap/VPS, resolve a obra, baixa manifesto+blocos
 * e **verifica Ed25519 + sha-256 no próprio alvo** (o device, quando roda no iPhone). Mede
 * connect, TTFB (1º byte de conteúdo), download e verify. Este é o instrumento da régua 4.1 —
 * o mesmo código roda no host (aferição) e no iPhone (campo).
 */
object ReaderProbe {

    fun readOnce(host: String, port: Int, bootstrapIdHex: String, resolveTimeoutMs: Long = 15_000): ReadSample {
        val client: P2pBackend = TramaBackend.client(TcpTransport(0), anonymous = false)
        val clock = TimeSource.Monotonic
        return try {
            val t0 = clock.markNow()
            client.dial(BootstrapAddr(host, port, bootstrapIdHex))
            val connectMs = t0.elapsedNow().inWholeMilliseconds

            val provider = awaitProvider(client, resolveTimeoutMs)
                ?: return fail("resolve não achou provider em ${resolveTimeoutMs}ms", connectMs)

            val tContent = clock.markNow()
            val manifestBlock = client.getManifest(provider, CampaignVectors.OBRA)
            val ttfbMs = tContent.elapsedNow().inWholeMilliseconds

            val decoded = ManifestCodec.decode(manifestBlock)
            val tDl = clock.markNow()
            val blocks = client.getBlocks(provider, decoded.manifest.blockCids.map { ContentId(it) })
            val downloadMs = tDl.elapsedNow().inWholeMilliseconds

            val tV = clock.markNow()
            // leitor confia na pubkey CONHECIDA do publicador (constante), sem derivar de semente
            val result = ChapterVerifier(CampaignVectors.CONTENT_PUB_KEY_HEX)
                .verify(manifestBlock, blocks.map { it.bytes })
            val verifyMs = tV.elapsedNow().inWholeMilliseconds
            val totalMs = t0.elapsedNow().inWholeMilliseconds

            when (result) {
                is ChapterVerifier.Result.Verified -> ReadSample(
                    true, "verified/${result.manifest.chapterId}", connectMs, ttfbMs, downloadMs, verifyMs, totalMs, result.chapter.size,
                )
                else -> ReadSample(false, "verify=$result", connectMs, ttfbMs, downloadMs, verifyMs, totalMs, 0)
            }
        } catch (e: Throwable) {
            ReadSample(false, "erro: ${e::class.simpleName}: ${e.message}", -1, -1, -1, -1, -1, 0)
        } finally {
            runCatching { client.close() }
        }
    }

    private fun awaitProvider(client: P2pBackend, timeoutMs: Long): Provider? {
        var waited = 0L
        while (waited < timeoutMs) {
            val ps = runCatching { client.resolve(CampaignVectors.OBRA) }.getOrDefault(emptyList())
            if (ps.isNotEmpty()) return ps.first()
            blockingSleep(200); waited += 200
        }
        return null
    }

    private fun fail(msg: String, connectMs: Long) = ReadSample(false, msg, connectMs, -1, -1, -1, -1, 0)
}
