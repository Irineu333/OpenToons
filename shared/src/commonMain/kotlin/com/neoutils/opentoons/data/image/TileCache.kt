package com.neoutils.opentoons.data.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cache LRU de tiles decodificados com **orçamento explícito de bytes** (design D5, task 5.8).
 * A chave é a chave de região do tile (`archivePath::entryName::srcTop::srcHeight::targetWidth`);
 * o tamanho é `width × height × 4` (ARGB_8888). Quando o total excede o orçamento, evicta os
 * menos recentemente usados — uma janela de poucos tiles cabe folgada em memória enquanto uma
 * página inteira (~69 MB) não caberia.
 *
 * O `LinkedHashMap` do Kotlin comum não tem *access order*, então a recência é mantida à mão:
 * `get`/`put` reinserem a chave no fim; a eviction remove do início.
 */
class TileCache(private val maxBytes: Long) {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, ImageBitmap>()
    private var bytes = 0L

    suspend fun get(key: String): ImageBitmap? = mutex.withLock {
        val value = entries.remove(key) ?: return@withLock null
        entries[key] = value // move para o fim (mais recente)
        value
    }

    suspend fun put(key: String, bitmap: ImageBitmap) = mutex.withLock {
        val previous = entries.remove(key)
        if (previous != null) bytes -= sizeOf(previous)
        entries[key] = bitmap
        bytes += sizeOf(bitmap)
        trim()
    }

    suspend fun contains(key: String): Boolean = mutex.withLock { entries.containsKey(key) }

    private fun trim() {
        val it = entries.entries.iterator()
        while (bytes > maxBytes && entries.size > 1 && it.hasNext()) {
            val eldest = it.next()
            bytes -= sizeOf(eldest.value)
            it.remove()
        }
    }

    private fun sizeOf(bitmap: ImageBitmap): Long =
        bitmap.width.toLong() * bitmap.height.toLong() * 4L

    companion object {
        /** Orçamento default (~64 MB): cabe a janela visível + prefetch, longe do OOM. */
        const val DEFAULT_BUDGET_BYTES: Long = 64L * 1024 * 1024
    }
}
