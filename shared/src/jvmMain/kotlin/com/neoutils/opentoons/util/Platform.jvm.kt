package com.neoutils.opentoons.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun nowMillis(): Long = System.currentTimeMillis()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun readImageSize(bytes: ByteArray): ImageSize? {
    ByteArrayInputStream(bytes).use { input ->
        val iis = ImageIO.createImageInputStream(input) ?: return null
        try {
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            return try {
                reader.setInput(iis, true, true)
                ImageSize(reader.getWidth(0), reader.getHeight(0))
            } catch (_: Exception) {
                null
            } finally {
                reader.dispose()
            }
        } finally {
            iis.close()
        }
    }
}
