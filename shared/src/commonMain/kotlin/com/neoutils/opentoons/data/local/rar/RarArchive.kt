package com.neoutils.opentoons.data.local.rar

import okio.FileSystem
import okio.Path.Companion.toPath

/** Lançada quando um formato/variante não é suportado neste marco (ex.: RAR5, RAR no iOS). */
class UnsupportedFormatException(message: String) : Exception(message)

/** Entrada extraída de um RAR: nome e bytes crus (modo não-lazy — design D5). */
class RarEntry(val name: String, val bytes: ByteArray)

/**
 * Descompactação RAR (design D4/D5, task 3.1). `expect object` com um único ponto de entrada
 * **não-lazy** (`extractAll`): como tudo vira OPZ no import, o RAR não precisa de leitura
 * aleatória por página — basta despejar as entradas uma vez e alimentar o escritor OPZ. Isso
 * minimiza a superfície nativa no iOS (sem seek por entrada) e mantém a leitura em regime
 * Okio-pura (só OPZ). O RAR vive **apenas no caminho de import**.
 *
 *  - `actual` JVM/Android → `junrar` (RAR4, Java puro).
 *  - `actual` iOS/Native → cinterop `unarr` (spike 1.1); enquanto o spike não fecha, recusa
 *    RAR com mensagem clara (fallback documentado D5 — degradação isolada por plataforma).
 */
expect object RarArchive {
    /** Extrai todas as entradas-arquivo do RAR em [path]. RAR4 apenas; RAR5 é recusado. */
    fun extractAll(path: String): List<RarEntry>
}

/**
 * Detecção de assinatura RAR (task 3.4). O bloco de marca do RAR4 é `Rar!\x1A\x07\x00`
 * (7 bytes); o do RAR5 é `Rar!\x1A\x07\x01\x00` (8 bytes) — diferenciados pelo 7º byte.
 * `junrar`/`unarr` **só cobrem RAR4**; RAR5 é recusado no import com mensagem clara.
 */
object RarFormat {

    // "Rar!\x1A\x07" — prefixo comum a RAR4 e RAR5.
    private val PREFIX = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)

    private fun head(path: String): ByteArray = FileSystem.SYSTEM.read(path.toPath()) {
        val buffer = ByteArray(8)
        var read = 0
        while (read < buffer.size) {
            val n = read(buffer, read, buffer.size - read)
            if (n == -1) break
            read += n
        }
        if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    private fun hasPrefix(head: ByteArray): Boolean =
        head.size >= 7 && PREFIX.indices.all { head[it] == PREFIX[it] }

    /** `true` se o arquivo tem a marca RAR5 (`...\x07\x01`). */
    fun isRar5(path: String): Boolean {
        val head = head(path)
        return hasPrefix(head) && head.size >= 7 && head[6] == 0x01.toByte()
    }

    /**
     * Recusa RAR5 com mensagem clara antes de chamar o descompactador (que só cobre RAR4).
     * Não faz nada para RAR4 ou para não-RAR (deixa o descompactador reportar).
     */
    fun requireNotRar5(path: String) {
        if (isRar5(path)) {
            throw UnsupportedFormatException(
                "RAR5 não é suportado neste marco. Reempacote o arquivo como CBZ ou RAR4.",
            )
        }
    }
}
