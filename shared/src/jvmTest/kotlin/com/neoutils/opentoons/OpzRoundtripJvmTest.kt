package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.data.local.opz.OpzWriter
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Roundtrip do escritor OPZ streaming (task 6.1 / D6): escreve um `.opz` STORED pura-Kotlin
 * emitindo página a página e reabre com o mesmo caminho da leitura em regime (Okio `openZip`
 * via [CbzArchive]) — lista, lê bytes e desserializa o `manifest.json`. Se o CRC/central
 * directory do escritor estivessem errados, o `openZip` falharia aqui.
 */
class OpzRoundtripJvmTest {

    private val pages = listOf(
        "001.jpg" to "bytes-da-pagina-1",
        "002.jpg" to "conteudo-maior-da-pagina-2",
    )

    private val chapterId = "chapter-uuid-1"
    private val obraId = "obra-uuid-1"

    private fun writeOpz(): String {
        val file = File.createTempFile("opentoons-opz", ".opz").apply { deleteOnExit() }
        val result = OpzWriter.write(
            fileSystem = FileSystem.SYSTEM,
            outputPath = file.absolutePath.toPath(),
            chapterId = chapterId,
            obraId = obraId,
        ) { sink ->
            pages.forEach { (name, content) -> sink.page(name, content.encodeToByteArray()) }
        }
        assertEquals(2, result.pageCount)
        assertEquals("001.jpg", result.firstPageName)
        return file.absolutePath
    }

    @Test
    fun opzReabreViaOpenZip_listaELeBytes() {
        val path = writeOpz()
        // manifest.json não é imagem → não aparece na listagem de páginas.
        assertEquals(listOf("001.jpg", "002.jpg"), CbzArchive.listImageEntries(path))
        assertEquals("bytes-da-pagina-1", CbzArchive.readEntry(path, "001.jpg").decodeToString())
        assertEquals("conteudo-maior-da-pagina-2", CbzArchive.readEntry(path, "002.jpg").decodeToString())
    }

    @Test
    fun manifesto_carregaChapterIdOrdemEPaginas_semDirection() {
        val path = writeOpz()
        val manifest = OpzReader.manifest(path)
        assertNotNull(manifest)
        // `direction` subiu para a obra (work.json) — o manifesto do capítulo carrega o
        // `chapterId` interno (D3/D4) e mantém o `detectedLayout`.
        assertEquals(chapterId, manifest.chapterId)
        assertEquals(obraId, manifest.obraId)
        assertEquals(listOf("001.jpg", "002.jpg"), manifest.pages.map { it.name })
        // campo assinado do ADR-0003 previsto e nulo neste marco
        assertEquals(null, manifest.chavePublicador)
    }

    @Test
    fun pageNames_seguemAOrdemDoManifesto() {
        val path = writeOpz()
        assertEquals(listOf("001.jpg", "002.jpg"), OpzReader.pageNames(path))
    }
}
