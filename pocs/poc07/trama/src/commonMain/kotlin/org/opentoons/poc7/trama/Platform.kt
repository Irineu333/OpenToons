package org.opentoons.poc7.trama

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/** Relógio de parede em millis — substitui `System.currentTimeMillis()` (TTL de anúncios). */
internal expect fun nowMillis(): Long

/** Espera bloqueante (poll de resolve) — `Thread.sleep` não existe em `commonMain`. */
internal fun blockingSleep(ms: Long) = runBlocking { delay(ms) }
