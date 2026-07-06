package org.opentoons.poc7.trama

import org.opentoons.poc7.api.Block
import org.opentoons.poc7.api.Blockstore
import org.opentoons.poc7.api.ChapterPublisher
import org.opentoons.poc7.api.ContentId
import org.opentoons.poc7.api.NodeKeys
import org.opentoons.poc7.api.ObraId

/**
 * poc-07 — vetores do TCK portados: o MESMO capítulo determinístico (3 páginas de 256 KiB =
 * 768 KiB, igual à campanha) e as chaves fixas. Fixado independente de backend.
 */
object TckVectors {
    val OBRA = ObraId("opentoons/serie-teste")
    const val CHAPTER_ID = "opentoons/serie-teste/cap-001"
    const val SEQ = 7L

    val contentKeys: NodeKeys = NodeKeys.fromSeed("poc7-content")
    val wrongKeys: NodeKeys = NodeKeys.fromSeed("poc7-imposter")

    val pages: List<ByteArray> = listOf(
        ByteArray(256 * 1024) { (it % 251).toByte() },
        ByteArray(256 * 1024) { (it % 241).toByte() },
        ByteArray(256 * 1024) { (it % 239).toByte() },
    )

    fun prepared(): ChapterPublisher.Prepared =
        ChapterPublisher.prepare(contentKeys, CHAPTER_ID, SEQ, pages)

    fun preparedWrongKey(): ChapterPublisher.Prepared =
        ChapterPublisher.prepare(wrongKeys, CHAPTER_ID, SEQ, pages)

    val chapterBytes: ByteArray by lazy {
        val total = ByteArray(pages.sumOf { it.size })
        var off = 0
        pages.forEach { it.copyInto(total, off); off += it.size }
        total
    }
}

/** Wrapper de ADULTERAÇÃO neutro: corrompe 1 byte do primeiro bloco servido. */
class TamperingBlockstore(private val inner: Blockstore) : Blockstore by inner {
    override fun block(id: ContentId): ByteArray? =
        inner.block(id)?.copyOf()?.also { it[0] = (it[0].toInt() xor 0x01).toByte() }
}
