package com.neoutils.opentoons.data.image

import androidx.compose.ui.graphics.ImageBitmap
import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.ui.reader.Tile
import com.neoutils.opentoons.util.ioDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Carrega o bitmap de um tile: resolve pelo [TileCache], senão lê os bytes codificados da
 * entrada do `.opz` e decodifica **por região** ([decodeRegion]). A chave de cache é a chave
 * de região da task 5.7 — `archivePath::entryName::srcTop::srcHeight::targetWidth`.
 *
 * Diferente do [ArchiveImageFetcher] (que passa os bytes ao Coil), o long strip decodifica e
 * cacheia os tiles fora do Coil: o produto é um `ImageBitmap` já recortado e reduzido, e o
 * orçamento de memória é do [TileCache], não do cache do Coil. Um cache pequeno dos **bytes
 * codificados** por entrada evita reabrir o zip e re-ler a página inteira a cada tile.
 */
class TileLoader {

    private val mutex = Mutex()
    private val encoded = LinkedHashMap<String, ByteArray>()
    private var encodedTotal = 0L

    suspend fun load(
        archivePath: String,
        entryName: String,
        tile: Tile,
        targetWidth: Int,
        cache: TileCache,
    ): ImageBitmap? {
        val key = key(archivePath, entryName, tile, targetWidth)
        cache.get(key)?.let { return it }
        val bitmap = withContext(ioDispatcher) {
            val bytes = encodedBytes(archivePath, entryName)
            decodeRegion(bytes, tile.srcTop, tile.srcHeight, targetWidth)
        } ?: return null
        cache.put(key, bitmap)
        return bitmap
    }

    private suspend fun encodedBytes(archivePath: String, entryName: String): ByteArray {
        val k = "$archivePath::$entryName"
        mutex.withLock {
            encoded.remove(k)?.let { encoded[k] = it; return it }
        }
        val data = CbzArchive.readEntry(archivePath, entryName)
        mutex.withLock {
            encoded[k] = data
            encodedTotal += data.size
            val it = encoded.entries.iterator()
            while (encodedTotal > MAX_ENCODED_BYTES && encoded.size > 1 && it.hasNext()) {
                val eldest = it.next()
                encodedTotal -= eldest.value.size
                it.remove()
            }
        }
        return data
    }

    companion object {
        /** Orçamento do cache de bytes codificados (~32 MB): poucas páginas em voo. */
        private const val MAX_ENCODED_BYTES = 32L * 1024 * 1024

        fun key(archivePath: String, entryName: String, tile: Tile, targetWidth: Int): String =
            "$archivePath::$entryName::${tile.srcTop}::${tile.srcHeight}::$targetWidth"
    }
}
