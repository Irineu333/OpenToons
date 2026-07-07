package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.ArchiveReader
import com.neoutils.opentoons.data.local.ContainerFormat
import com.neoutils.opentoons.data.local.ContainerFormats
import com.neoutils.opentoons.data.local.ZipArchiveReader
import com.neoutils.opentoons.data.local.rar.RarFormat
import com.neoutils.opentoons.data.local.rar.UnsupportedFormatException
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Desambiguação unidade vs pacote (D3, task 4.2/6.1), detecção de formato por magic bytes
 * (task 4.1) e recusa de RAR5 (task 3.4/6.1).
 */
class ImportFormatsJvmTest {

    private fun zipWith(vararg names: String): String {
        val file = File.createTempFile("opentoons-fmt", ".zip").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            names.forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return file.absolutePath
    }

    @Test
    fun detectaZipPorMagicBytes() {
        val path = zipWith("ch1/001.jpg")
        assertEquals(ContainerFormat.ZIP, ContainerFormats.detect(path))
    }

    @Test
    fun unidade_entradasSaoImagens_naoEhPacote() {
        val path = zipWith("001.jpg", "002.jpg", "ComicInfo.xml")
        val entries = ZipArchiveReader(path).entryNames()
        assertFalse(entries.any { ArchiveReader.isArchive(it) })
    }

    @Test
    fun pacote_entradasSaoArquivosArquivo_ehPacote() {
        val path = zipWith("vol1.cbz", "vol2.cbr")
        val entries = ZipArchiveReader(path).entryNames()
        assertTrue(entries.any { ArchiveReader.isArchive(it) })
    }

    @Test
    fun arquivoNaoReconhecido_lancaUnsupported() {
        val file = File.createTempFile("opentoons-bogus", ".bin").apply {
            deleteOnExit()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        assertFailsWith<UnsupportedFormatException> { ContainerFormats.detect(file.absolutePath) }
    }

    private fun rarHeader(seventhByte: Int): String {
        val file = File.createTempFile("opentoons-rar", ".rar").apply { deleteOnExit() }
        // "Rar!\x1A\x07" + byte de versão (0x00 = RAR4, 0x01 = RAR5) + preenchimento.
        file.writeBytes(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, seventhByte.toByte(), 0x00))
        return file.absolutePath
    }

    @Test
    fun rar5_ehDetectadoERecusado() {
        val rar5 = rarHeader(0x01)
        assertTrue(RarFormat.isRar5(rar5))
        assertFailsWith<UnsupportedFormatException> { RarFormat.requireNotRar5(rar5) }
    }

    @Test
    fun rar4_naoEhRar5_eNaoRecusa() {
        val rar4 = rarHeader(0x00)
        assertFalse(RarFormat.isRar5(rar4))
        RarFormat.requireNotRar5(rar4) // não lança
    }
}
