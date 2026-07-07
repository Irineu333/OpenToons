package com.neoutils.opentoons.data.local.rar

import okio.FileSystem
import okio.Path.Companion.toPath

/** Lançada quando um formato/variante não é suportado neste marco (ex.: RAR5, RAR no iOS). */
class UnsupportedFormatException(message: String) : Exception(message)

/**
 * Descompactação RAR (design D4/D5, task 3.1). Handle **por-entrada** sobre um `.cbr`/`.rar`:
 * lista os nomes (metadados, sem dados) e lê os bytes de uma entrada sob demanda. O import
 * escreve o OPZ em streaming (pico = 1 página), então o RAR **não** é despejado inteiro na
 * memória — volumes grandes (ex. ~284 MB) estouravam o heap do Android com o modo extract-all.
 * A leitura em regime segue Okio-pura (só OPZ); o RAR vive **apenas no caminho de import**.
 *
 *  - `actual` JVM/Android → `junrar` (RAR4, Java puro; extração por entrada, não-solid).
 *  - `actual` iOS/Native → **RAR é não-objetivo**: recusa por design (sem cinterop), o iOS
 *    lê OPZ e importa CBZ/ZIP; o picker nem oferece RAR (`ImportFormats`).
 */
expect class RarArchive(path: String) : AutoCloseable {
    /** Nomes das entradas-arquivo (sem diretórios), `/` como separador. RAR5 é recusado. */
    fun entryNames(): List<String>

    /** Bytes de uma entrada, extraídos sob demanda. */
    fun read(name: String): ByteArray

    override fun close()
}

/**
 * Detecção de assinatura RAR (task 3.4). O bloco de marca do RAR4 é `Rar!\x1A\x07\x00`
 * (7 bytes); o do RAR5 é `Rar!\x1A\x07\x01\x00` (8 bytes) — diferenciados pelo 7º byte.
 * `junrar` **só cobre RAR4**; RAR5 é recusado no import com mensagem clara (não-objetivo).
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
