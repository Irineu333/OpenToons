package com.neoutils.opentoons.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIImage

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

// Native não expõe Dispatchers.IO; Default cobre o offloading de IO do Marco 1.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

// RAR no iOS é não-objetivo (sem cinterop) → o iOS suporta CBZ/ZIP, não RAR.
actual val rarImportSupported: Boolean = false

@OptIn(ExperimentalForeignApi::class)
actual fun readImageSize(bytes: ByteArray): ImageSize? {
    if (bytes.isEmpty()) return null
    val data = bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
    val image = UIImage.imageWithData(data) ?: return null
    val scale = image.scale
    val (w, h) = image.size.useContents { width to height }
    val pw = (w * scale).toInt()
    val ph = (h * scale).toInt()
    return if (pw > 0 && ph > 0) ImageSize(pw, ph) else null
}
