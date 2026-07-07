package com.neoutils.opentoons.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Android: decodifica com `inSampleSize` (não estoura memória), escala fino ao lado alvo e
 * comprime em WebP (lossy, q=80). Bitmap/decoder do próprio SO — sem dependência nova.
 */
actual object CoverEncoder {
    actual fun encodeThumbnail(source: ByteArray, maxDimension: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) return null

        var sample = 1
        while (w / (sample * 2) >= maxDimension && h / (sample * 2) >= maxDimension) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            source, 0, source.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val longest = maxOf(decoded.width, decoded.height)
        val scaled = if (longest > maxDimension) {
            val ratio = maxDimension.toFloat() / longest
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            ).also { if (it != decoded) decoded.recycle() }
        } else {
            decoded
        }

        return ByteArrayOutputStream().use { out ->
            @Suppress("DEPRECATION")
            scaled.compress(Bitmap.CompressFormat.WEBP, 80, out)
            scaled.recycle()
            out.toByteArray()
        }
    }
}
