package com.neoutils.opentoons.data.local.rar

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `actual` Android (task 3.2): `junrar` (RAR4, Java puro). Idêntico ao JVM — headers listados
 * uma vez e extração **por entrada** sob demanda, para o import escrever OPZ em streaming sem
 * estourar o heap. RAR só no caminho de import (D5). RAR5 recusado antes.
 */
actual class RarArchive actual constructor(path: String) : AutoCloseable {

    private val archive: Archive
    private val headers: Map<String, FileHeader>

    init {
        RarFormat.requireNotRar5(path)
        archive = Archive(File(path))
        headers = archive.fileHeaders
            .filter { !it.isDirectory }
            .associateBy { it.fileName.replace('\\', '/') }
    }

    actual fun entryNames(): List<String> = headers.keys.toList()

    actual fun read(name: String): ByteArray {
        val header = headers[name] ?: error("Entrada ausente no RAR: $name")
        return ByteArrayOutputStream(header.fullUnpackSize.toInt().coerceAtLeast(0)).use { out ->
            archive.extractFile(header, out)
            out.toByteArray()
        }
    }

    actual override fun close() {
        archive.close()
    }
}
