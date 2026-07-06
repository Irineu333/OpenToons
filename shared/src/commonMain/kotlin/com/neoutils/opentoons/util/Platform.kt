package com.neoutils.opentoons.util

import kotlinx.coroutines.CoroutineDispatcher

/** Epoch em milissegundos (para carimbar progresso/import). */
expect fun nowMillis(): Long

/** Dispatcher de IO por plataforma (Native não expõe `Dispatchers.IO`). */
expect val ioDispatcher: CoroutineDispatcher
