package com.neoutils.opentoons.data.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.RenderingHints
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * `actual` JVM/Desktop (task 5.3): `ImageReadParam.setSourceRegion` + sub-sampling na leitura,
 * decodificando só a faixa pedida sem materializar a página inteira. Um redimensionamento
 * bilinear final acerta a largura exata (nunca acima da nativa). Falha → fallback de leitura
 * completa (task 5.5). WebP no Desktop é coberto pelo reader do TwelveMonkeys (ServiceLoader).
 */
actual fun decodeRegion(
    bytes: ByteArray,
    srcTop: Int,
    srcHeight: Int,
    targetWidth: Int,
): ImageBitmap? = runCatching {
    ByteArrayInputStream(bytes).use { input ->
        val iis = ImageIO.createImageInputStream(input) ?: return@runCatching null
        try {
            val readers = ImageIO.getImageReaders(iis)
            if (!readers.hasNext()) return@runCatching null
            val reader = readers.next()
            try {
                reader.setInput(iis, true, true)
                val w = reader.getWidth(0)
                val h = reader.getHeight(0)
                if (w <= 0 || h <= 0) return@runCatching null

                val top = srcTop.coerceIn(0, h)
                val regionHeight = if (srcHeight > 0) srcHeight.coerceAtMost(h - top) else h - top
                val targetW = clampWidth(targetWidth, w, regionHeight)

                val param = reader.defaultReadParam
                if (srcHeight > 0) param.sourceRegion = Rectangle(0, top, w, regionHeight)
                val sample = sampleSize(w, targetW)
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)

                val decoded = reader.read(0, param)
                val scaled = if (decoded.width > targetW) scaleDown(decoded, targetW) else decoded
                scaled.toComposeImageBitmap()
            } finally {
                reader.dispose()
            }
        } finally {
            iis.close()
        }
    }
}.getOrNull()

private fun clampWidth(requested: Int, nativeWidth: Int, regionHeight: Int): Int {
    var target = requested.coerceIn(1, nativeWidth.coerceAtLeast(1)).coerceAtMost(MAX_TEXTURE_PX)
    if (regionHeight > 0 && nativeWidth > 0) {
        val projectedH = regionHeight.toLong() * target / nativeWidth
        if (projectedH > MAX_TEXTURE_PX) {
            target = (target.toLong() * MAX_TEXTURE_PX / projectedH).toInt().coerceAtLeast(1)
        }
    }
    return target
}

private fun scaleDown(src: BufferedImage, targetWidth: Int): BufferedImage {
    val targetH = (src.height.toLong() * targetWidth / src.width).toInt().coerceAtLeast(1)
    val out = BufferedImage(targetWidth, targetH, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(src, 0, 0, targetWidth, targetH, null)
    g.dispose()
    return out
}

private fun sampleSize(srcWidth: Int, targetWidth: Int): Int {
    var s = 1
    while (targetWidth > 0 && srcWidth / (s * 2) >= targetWidth) s *= 2
    return s
}
