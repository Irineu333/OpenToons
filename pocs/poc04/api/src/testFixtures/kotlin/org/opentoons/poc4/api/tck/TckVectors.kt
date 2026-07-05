package org.opentoons.poc4.api.tck

import org.opentoons.poc4.api.Block
import org.opentoons.poc4.api.ChapterPublisher
import org.opentoons.poc4.api.NodeKeys
import org.opentoons.poc4.api.ObraId

/**
 * PoC poc-04 — vetores do TCK (design D4): o MESMO capítulo determinístico para qualquer
 * backend, herdado do TestChapter do poc-02 (3 páginas de 256 KiB, padrões fixos), com
 * chave de conteúdo determinística. Fixado ANTES de qualquer adapter existir.
 */
object TckVectors {

    val OBRA = ObraId("opentoons/serie-teste")
    const val CHAPTER_ID = "opentoons/serie-teste/cap-001"
    const val SEQ = 7L

    /** Chave que ASSINA o manifesto (a "editora"); o app confia nesta pubkey. */
    val contentKeys: NodeKeys = NodeKeys.fromSeed("poc4-content")

    /** Chave ERRADA para o cenário de rejeição por assinatura de terceiro. */
    val wrongKeys: NodeKeys = NodeKeys.fromSeed("poc4-imposter")

    val pages: List<ByteArray> = listOf(
        ByteArray(256 * 1024) { (it % 251).toByte() },
        ByteArray(256 * 1024) { (it % 241).toByte() },
        ByteArray(256 * 1024) { (it % 239).toByte() },
    )

    /** A publicação canônica: manifesto assinado + blocos endereçados por sha-256. */
    fun prepared(): ChapterPublisher.Prepared =
        ChapterPublisher.prepare(contentKeys, CHAPTER_ID, SEQ, pages)

    /** A mesma obra assinada pela chave ERRADA (cenário de rejeição BadSignature). */
    fun preparedWrongKey(): ChapterPublisher.Prepared =
        ChapterPublisher.prepare(wrongKeys, CHAPTER_ID, SEQ, pages)

    val chapterBytes: ByteArray by lazy {
        val total = ByteArray(pages.sumOf { it.size })
        var off = 0
        pages.forEach { it.copyInto(total, off); off += it.size }
        total
    }
}

/**
 * Wrapper de ADULTERAÇÃO neutro (D4): corrompe 1 byte do primeiro bloco servido, sem tocar
 * em nenhum backend — o mecanismo de rejeição do TCK/matriz funciona idêntico nos dois.
 */
class TamperingBlockstore(
    private val inner: org.opentoons.poc4.api.Blockstore,
) : org.opentoons.poc4.api.Blockstore by inner {
    override fun block(id: org.opentoons.poc4.api.ContentId): ByteArray? =
        inner.block(id)?.copyOf()?.also { it[0] = (it[0].toInt() xor 0x01).toByte() }
}
