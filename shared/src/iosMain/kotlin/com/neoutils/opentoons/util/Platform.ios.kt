package com.neoutils.opentoons.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

// Native não expõe Dispatchers.IO; Default cobre o offloading de IO do Marco 1.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

// RAR no iOS é não-objetivo (sem cinterop) → o iOS suporta CBZ/ZIP, não RAR.
actual val rarImportSupported: Boolean = false
