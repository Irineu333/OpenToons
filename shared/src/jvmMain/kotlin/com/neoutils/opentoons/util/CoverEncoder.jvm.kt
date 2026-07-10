package com.neoutils.opentoons.util

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Desktop/JVM: `ImageIO` decodifica, escala com interpolação bilinear e reescreve em **PNG**
 * (fallback — o `ImageIO` padrão não codifica WebP; D5 permite PNG/JPEG). Cache regenerável,
 * formato não contratual; o Coil decodifica por conteúdo.
 */
actual object CoverEncoder {
    actual fun encodeThumbnail(source: ByteArray, maxDimension: Int): ByteArray? {
        val original = runCatching {
            ByteArrayInputStream(source).use { ImageIO.read(it) }
        }.getOrNull() ?: return null

        val longest = maxOf(original.width, original.height)
        if (longest <= 0) return null
        val ratio = if (longest > maxDimension) maxDimension.toFloat() / longest else 1f
        val tw = (original.width * ratio).toInt().coerceAtLeast(1)
        val th = (original.height * ratio).toInt().coerceAtLeast(1)

        val scaled = BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(original, 0, 0, tw, th, null)
            dispose()
        }

        return ByteArrayOutputStream().use { out ->
            if (!ImageIO.write(scaled, "png", out)) return null
            out.toByteArray()
        }
    }

    actual fun isDecodable(source: ByteArray): Boolean = runCatching {
        ByteArrayInputStream(source).use { ImageIO.read(it) }
    }.getOrNull() != null
}
