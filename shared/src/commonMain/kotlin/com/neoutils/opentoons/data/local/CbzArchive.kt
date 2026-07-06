package com.neoutils.opentoons.data.local

import com.neoutils.opentoons.util.NaturalOrder
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Leitura de `.cbz`/`.zip` via Okio `openZip` (design D5, spec content-import). Roda em
 * JVM/Android/Native (iOS) e cobre STORED+DEFLATE. Lê o diretório central e serve cada
 * entrada **sob demanda**, sem descompactar o capítulo inteiro — memória limitada.
 */
object CbzArchive {

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jxl", "heic",
    )

    /** Entradas de imagem do arquivo, na ordenação natural dos nomes (task 3.5). */
    fun listImageEntries(archivePath: String): List<String> {
        val zip = FileSystem.SYSTEM.openZip(archivePath.toPath())
        val root = "/".toPath()
        return zip.listRecursively(root)
            .filter { zip.metadataOrNull(it)?.isRegularFile == true }
            .map { it.toString().removePrefix("/") }
            .filter { it.substringAfterLast('.', "").lowercase() in imageExtensions }
            .toList()
            .sortedWith(NaturalOrder)
    }

    /** Lê os bytes de uma entrada, sob demanda. */
    fun readEntry(archivePath: String, entryName: String): ByteArray {
        val zip = FileSystem.SYSTEM.openZip(archivePath.toPath())
        val path = "/$entryName".toPath()
        return zip.read(path) { readByteArray() }
    }
}
