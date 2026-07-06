package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.CbzArchive
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * De-risco do Okio `openZip` (spike 2.3 / D5): cria um `.cbz` real (STORED/DEFLATE),
 * lista entradas de imagem na ordem natural e lê uma página sob demanda. O código de
 * [CbzArchive] é `commonMain` — este teste valida o mecanismo no host (o mesmo caminho
 * roda em Native/iOS; ver 8.2 para o E2E on-device).
 */
class CbzArchiveJvmTest {

    private fun buildCbz(): String {
        val file = File.createTempFile("opentoons-test", ".cbz").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            // fora de ordem e com um não-imagem, para exercitar filtro + ordenação natural
            listOf("pag10.jpg", "ComicInfo.xml", "pag2.jpg", "pag1.jpg").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("bytes-of-$name".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return file.absolutePath
    }

    @Test
    fun listaImagens_naOrdemNatural_ignorandoNaoImagem() {
        val entries = CbzArchive.listImageEntries(buildCbz())
        assertEquals(listOf("pag1.jpg", "pag2.jpg", "pag10.jpg"), entries)
    }

    @Test
    fun leEntrada_sobDemanda() {
        val path = buildCbz()
        val bytes = CbzArchive.readEntry(path, "pag2.jpg")
        assertEquals("bytes-of-pag2.jpg", bytes.decodeToString())
        assertTrue(bytes.isNotEmpty())
    }

    private fun buildMultiChapterCbz(): String {
        val file = File.createTempFile("opentoons-multi", ".cbz").apply { deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf(
                "Chapter 2/002.jpg", "Chapter 2/001.jpg",
                "Chapter 10/001.jpg",
                "Chapter 1/002.jpg", "Chapter 1/001.jpg",
            ).forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("x".encodeToByteArray())
                zip.closeEntry()
            }
        }
        return file.absolutePath
    }

    @Test
    fun agrupaCapitulosPorPasta_emOrdemNatural() {
        val chapters = CbzArchive.chapters(buildMultiChapterCbz())
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 10"), chapters.map { it.dir })
        // páginas de cada capítulo ordenadas naturalmente
        assertEquals(listOf("Chapter 1/001.jpg", "Chapter 1/002.jpg"), chapters[0].entries)
        assertEquals(2, chapters[1].entries.size)
        assertEquals(1, chapters[2].entries.size)
    }

    @Test
    fun cbzPlano_ehUmCapituloSo() {
        val chapters = CbzArchive.chapters(buildCbz())
        assertEquals(1, chapters.size)
        assertEquals("", chapters[0].dir)
        assertEquals(listOf("pag1.jpg", "pag2.jpg", "pag10.jpg"), chapters[0].entries)
    }
}
