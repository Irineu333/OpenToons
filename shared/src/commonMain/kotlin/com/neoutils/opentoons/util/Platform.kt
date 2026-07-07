package com.neoutils.opentoons.util

import kotlinx.coroutines.CoroutineDispatcher

/** Epoch em milissegundos (para carimbar progresso/import). */
expect fun nowMillis(): Long

/** Dispatcher de IO por plataforma (Native não expõe `Dispatchers.IO`). */
expect val ioDispatcher: CoroutineDispatcher

/**
 * Se esta plataforma sabe descompactar RAR no import (D4/D5). JVM/Android usam `junrar`
 * (RAR4); no iOS/Native **RAR é não-objetivo** (sem cinterop), então não suporta — o picker
 * não deve oferecer CBR/RAR onde isso sempre falharia (ver [ImportFormats]).
 */
expect val rarImportSupported: Boolean
