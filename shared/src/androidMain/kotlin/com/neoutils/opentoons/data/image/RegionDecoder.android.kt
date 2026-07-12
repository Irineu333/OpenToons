package com.neoutils.opentoons.data.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * `actual` Android (task 5.2): `BitmapRegionDecoder` decodifica só a faixa pedida, sem
 * materializar a página inteira. `inSampleSize` reduz na leitura; um `createScaledBitmap`
 * final acerta a largura exata sem nunca ultrapassar a nativa. Falha/format­o não suportado
 * → fallback de decode inteiro com sub-sampling (task 5.5).
 */
actual fun decodeRegion(
    bytes: ByteArray,
    srcTop: Int,
    srcHeight: Int,
    targetWidth: Int,
): ImageBitmap? = runCatching {
    if (srcHeight <= 0) return@runCatching decodeFull(bytes, targetWidth)?.asImageBitmap()

    @Suppress("DEPRECATION") // newInstance(ByteArray,…) cobre minSdk 26; a variante nova é 31+.
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
    try {
        val w = decoder.width
        val h = decoder.height
        val targetW = clampWidth(targetWidth, w, srcHeight)
        val top = srcTop.coerceIn(0, h)
        val bottom = (srcTop + srcHeight).coerceIn(top, h)
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, targetW) }
        val region = decoder.decodeRegion(Rect(0, top, w, bottom), opts)
            ?: return@runCatching decodeFull(bytes, targetWidth)?.asImageBitmap()
        scaleTo(region, targetW).asImageBitmap()
    } finally {
        decoder.recycle()
    }
}.getOrNull()

private fun decodeFull(bytes: ByteArray, targetWidth: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val w = bounds.outWidth
    val h = bounds.outHeight
    if (w <= 0 || h <= 0) return null
    val targetW = clampWidth(targetWidth, w, h)
    val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(w, targetW) }
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
    return scaleTo(bmp, targetW)
}

/** Largura-alvo: nunca acima da nativa, e limitada para o bitmap não exceder o max de textura. */
private fun clampWidth(requested: Int, nativeWidth: Int, regionHeight: Int): Int {
    var target = requested.coerceIn(1, nativeWidth.coerceAtLeast(1))
    target = target.coerceAtMost(MAX_TEXTURE_PX)
    // Altura projetada a essa largura não pode exceder o max de textura.
    if (regionHeight > 0 && nativeWidth > 0) {
        val projectedH = regionHeight.toLong() * target / nativeWidth
        if (projectedH > MAX_TEXTURE_PX) {
            target = (target.toLong() * MAX_TEXTURE_PX / projectedH).toInt().coerceAtLeast(1)
        }
    }
    return target
}

private fun scaleTo(bmp: Bitmap, targetWidth: Int): Bitmap {
    if (bmp.width <= targetWidth) return bmp
    val targetH = (bmp.height.toLong() * targetWidth / bmp.width).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(bmp, targetWidth, targetH, true)
    if (scaled != bmp) bmp.recycle()
    return scaled
}

private fun sampleSize(srcWidth: Int, targetWidth: Int): Int {
    var s = 1
    while (targetWidth > 0 && srcWidth / (s * 2) >= targetWidth) s *= 2
    return s
}
