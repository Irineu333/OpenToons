package com.neoutils.opentoons.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.neoutils.opentoons.domain.model.ThumbnailImage
import okio.Buffer
import okio.FileSystem

/**
 * Fetcher do Coil 3 para uma **thumbnail em memória** ([ThumbnailImage]): entrega bytes já
 * codificados (WebP/PNG/JPEG) direto de um buffer, sem tocar o disco. Serve a galeria de capa
 * na revisão de import (edit-import-metadata), onde a origem ainda **não** foi materializada.
 */
class ThumbnailImageFetcher(
    private val data: ThumbnailImage,
) : Fetcher {

    override suspend fun fetch(): FetchResult = SourceFetchResult(
        source = ImageSource(
            source = Buffer().apply { write(data.bytes) },
            fileSystem = FileSystem.SYSTEM,
        ),
        mimeType = null,
        dataSource = DataSource.MEMORY,
    )

    class Factory : Fetcher.Factory<ThumbnailImage> {
        override fun create(
            data: ThumbnailImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = ThumbnailImageFetcher(data)
    }
}

/** Chave de cache do Coil para a thumbnail em memória (o `chapterId` da candidata). */
class ThumbnailImageKeyer : Keyer<ThumbnailImage> {
    override fun key(data: ThumbnailImage, options: Options): String = data.key
}
