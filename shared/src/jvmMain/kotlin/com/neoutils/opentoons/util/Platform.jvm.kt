package com.neoutils.opentoons.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual fun nowMillis(): Long = System.currentTimeMillis()

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

// junrar (RAR4, Java puro) cobre o Desktop.
actual val rarImportSupported: Boolean = true
