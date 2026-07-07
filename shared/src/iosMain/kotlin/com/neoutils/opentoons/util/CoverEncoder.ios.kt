package com.neoutils.opentoons.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * iOS: `UIImage` decodifica, redesenha reduzido num contexto gráfico e reexporta em **JPEG**
 * (fallback — sem encoder WebP nativo simples; D5 permite JPEG). Cache regenerável, formato
 * não contratual.
 */
@OptIn(ExperimentalForeignApi::class)
actual object CoverEncoder {
    actual fun encodeThumbnail(source: ByteArray, maxDimension: Int): ByteArray? {
        if (source.isEmpty()) return null
        val data = source.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = source.size.toULong())
        }
        val image = UIImage.imageWithData(data) ?: return null

        val (w, h) = image.size.useContents { width to height }
        if (w <= 0.0 || h <= 0.0) return null
        val longest = maxOf(w, h)
        val ratio = if (longest > maxDimension) maxDimension.toDouble() / longest else 1.0
        val tw = w * ratio
        val th = h * ratio

        UIGraphicsBeginImageContextWithOptions(CGSizeMake(tw, th), true, 1.0)
        image.drawInRect(CGRectMake(0.0, 0.0, tw, th))
        val scaled = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()

        val jpeg = scaled?.let { UIImageJPEGRepresentation(it, 0.8) } ?: return null
        val length = jpeg.length.toInt()
        if (length == 0) return null
        val out = ByteArray(length)
        out.usePinned { pinned -> memcpy(pinned.addressOf(0), jpeg.bytes, jpeg.length) }
        return out
    }
}
