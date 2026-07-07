package com.neoutils.opentoons.data.local.rar

import com.github.junrar.Archive
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `actual` JVM (task 3.2): `junrar` (RAR4, Java puro — zero build nativo). Itera as entradas
 * uma vez e despeja os bytes (modo não-lazy, D5). RAR5 é recusado antes (junrar não cobre).
 */
actual object RarArchive {
    actual fun extractAll(path: String): List<RarEntry> {
        RarFormat.requireNotRar5(path)
        val entries = ArrayList<RarEntry>()
        Archive(File(path)).use { archive ->
            var header = archive.nextFileHeader()
            while (header != null) {
                if (!header.isDirectory) {
                    val out = ByteArrayOutputStream()
                    archive.extractFile(header, out)
                    entries += RarEntry(header.fileName.replace('\\', '/'), out.toByteArray())
                }
                header = archive.nextFileHeader()
            }
        }
        return entries
    }
}
