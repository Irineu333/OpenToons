package com.neoutils.opentoons.data.image

import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.neoutils.opentoons.domain.model.CoverImage
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Fetcher do Coil 3 para a **capa de obra** (`cover.webp`, D5): entrega o arquivo derivado
 * direto do storage próprio via Okio, sem abrir nenhum `.opz`. Serve a grade e o detalhe. O
 * decode fica a cargo do Coil (o conteúdo pode ser WebP/PNG/JPEG conforme o alvo — o formato
 * da thumbnail não é contratual).
 */
class CoverImageFetcher(
    private val data: CoverImage,
) : Fetcher {

    override suspend fun fetch(): FetchResult = SourceFetchResult(
        source = ImageSource(file = data.path.toPath(), fileSystem = FileSystem.SYSTEM),
        mimeType = null,
        dataSource = DataSource.DISK,
    )

    class Factory : Fetcher.Factory<CoverImage> {
        override fun create(
            data: CoverImage,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = CoverImageFetcher(data)
    }
}

/** Chave de cache do Coil para a capa de obra. */
class CoverImageKeyer : Keyer<CoverImage> {
    override fun key(data: CoverImage, options: Options): String = data.path
}
