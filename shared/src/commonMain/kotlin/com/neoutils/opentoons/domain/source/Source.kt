package com.neoutils.opentoons.domain.source

import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.Page

/**
 * Seam de fonte de conteúdo (spec content-import, design D2).
 *
 * Isola a **origem dos bytes** de leitura atrás de um contrato estável. No Marco 1 a única
 * implementação é `LocalImportSource` (lê do `.cbz` próprio via Okio `openZip`). No Marco 2
 * a rede entra como **mais uma** `Source` (`NetworkSource`) — as camadas de render e
 * biblioteca dependem só deste contrato e não mudam.
 */
interface Source {
    /** Identifica a origem; casa com [Chapter.sourceKey]. Ex.: "local", "network". */
    val key: String

    /** Lista as páginas de um capítulo, já na ordem de leitura, sem carregar bytes. */
    suspend fun pages(chapter: Chapter): List<Page>

    /** Lê os bytes de uma única página, sob demanda (leitura lazy — spec/D5). */
    suspend fun readPage(chapter: Chapter, page: Page): ByteArray
}

/**
 * Resolve a `Source` de um capítulo pela sua [Chapter.sourceKey]. Ponto único onde novas
 * origens são registradas — o render/biblioteca só falam com o registry.
 */
class SourceRegistry(sources: List<Source>) {
    private val byKey = sources.associateBy { it.key }

    fun forKey(key: String): Source =
        byKey[key] ?: error("Nenhuma Source registrada para a chave '$key'")

    fun forChapter(chapter: Chapter): Source = forKey(chapter.sourceKey)
}
