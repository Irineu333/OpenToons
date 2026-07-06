package com.neoutils.opentoons.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.domain.model.ArchiveImage
import com.neoutils.opentoons.util.ioDispatcher
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

/**
 * Fetcher do Coil 3 que carrega uma página **sob demanda** a partir do `.cbz` próprio via
 * Okio `openZip` (task 6.5). Decode/cache/sub-sampling ficam a cargo do Coil/Telephoto —
 * aqui só entregamos os bytes da entrada. Não há rede (100% offline).
 */
class ArchiveImageFetcher(
    private val data: ArchiveImage,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bytes = withContext(ioDispatcher) {
            CbzArchive.readEntry(data.archivePath, data.entryName)
        }
        val buffer = Buffer().apply { write(bytes) }
        return SourceFetchResult(
            source = ImageSource(source = buffer, fileSystem = FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ArchiveImage> {
        override fun create(
            data: ArchiveImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ArchiveImageFetcher(data)
    }
}

/** Chave de cache do Coil para páginas do arquivo. */
class ArchiveImageKeyer : Keyer<ArchiveImage> {
    override fun key(data: ArchiveImage, options: Options): String =
        "${data.archivePath}::${data.entryName}"
}
