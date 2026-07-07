package com.neoutils.opentoons.data.source

import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.Page
import com.neoutils.opentoons.domain.source.Source
import com.neoutils.opentoons.util.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * `Source` local: serve as páginas do **`.opz` próprio** do capítulo via Okio `openZip`
 * (leitura em regime intocada — D1/D5). A ordem das páginas vem do `manifest.json` do OPZ
 * (task 2.3), com fallback para a ordenação natural. É a única origem hoje; o `NetworkSource`
 * do Marco 2 implementa o mesmo contrato (D2/seam).
 */
class LocalImportSource : Source {

    override val key: String = KEY

    override suspend fun pages(chapter: Chapter): List<Page> = withContext(ioDispatcher) {
        OpzReader.pageNames(chapter.archivePath)
            .mapIndexed { i, entry -> Page(index = i, entryName = entry) }
    }

    override suspend fun readPage(chapter: Chapter, page: Page): ByteArray =
        withContext(ioDispatcher) {
            CbzArchive.readEntry(chapter.archivePath, page.entryName)
        }

    companion object {
        const val KEY = "local"
    }
}
