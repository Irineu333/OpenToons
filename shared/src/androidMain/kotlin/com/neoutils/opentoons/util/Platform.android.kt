package com.neoutils.opentoons.util

import android.graphics.BitmapFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun nowMillis(): Long = System.currentTimeMillis()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

// junrar (RAR4, Java puro) cobre o Android.
actual val rarImportSupported: Boolean = true

actual fun readImageSize(bytes: ByteArray): ImageSize? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    return if (opts.outWidth > 0 && opts.outHeight > 0) {
        ImageSize(opts.outWidth, opts.outHeight)
    } else {
        null
    }
}
