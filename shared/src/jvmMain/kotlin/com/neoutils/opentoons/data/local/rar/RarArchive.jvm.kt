package com.neoutils.opentoons.data.local.rar

import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `actual` JVM (task 3.2): `junrar` (RAR4, Java puro — zero build nativo). Lista os headers
 * uma vez (sem dados) e extrai cada entrada **sob demanda** (`extractFile`), para o import
 * escrever o OPZ em streaming sem manter o arquivo inteiro em memória. RAR5 é recusado antes.
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
