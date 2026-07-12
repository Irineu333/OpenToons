package com.neoutils.opentoons.data.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * `actual` iOS (task 5.4): recorta a faixa pedida via Skia (o backend de render do Compose no
 * iOS) e devolve o subset já reduzido à largura-alvo. Diferente de Android/JVM, esta via
 * **decodifica a página inteira** antes de recortar (`makeFromEncoded`) — é o fallback
 * documentado (task 5.5), aceitando o custo de memória naquela página. A região nativa via
 * `CGImageSourceCreateImageAtIndex` + `CGImageCreateWithImageInRect` (sem decode inteiro) é um
 * follow-up; a saída em pixel é idêntica, muda só o pico de memória por tile.
 *
 * Ignora a orientação EXIF (política de [ExifPolicy]): `makeFromEncoded` entrega o bitstream.
 */
actual fun decodeRegion(
    bytes: ByteArray,
    srcTop: Int,
    srcHeight: Int,
    targetWidth: Int,
): ImageBitmap? = runCatching {
    val image = SkiaImage.makeFromEncoded(bytes)
    val w = image.width
    val h = image.height
    if (w <= 0 || h <= 0) return@runCatching null

    val top = srcTop.coerceIn(0, h)
    val regionHeight = if (srcHeight > 0) srcHeight.coerceAtMost(h - top) else h - top
    if (regionHeight <= 0) return@runCatching null
    val targetW = clampWidth(targetWidth, w, regionHeight)
    val targetH = (regionHeight.toLong() * targetW / w).toInt().coerceAtLeast(1)

    val surface = Surface.makeRasterN32Premul(targetW, targetH)
    surface.canvas.drawImageRect(
        image,
        Rect.makeXYWH(0f, top.toFloat(), w.toFloat(), regionHeight.toFloat()),
        Rect.makeWH(targetW.toFloat(), targetH.toFloat()),
    )
    surface.makeImageSnapshot().toComposeImageBitmap()
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
