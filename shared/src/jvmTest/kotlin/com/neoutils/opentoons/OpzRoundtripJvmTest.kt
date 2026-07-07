package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.data.local.opz.OpzPageInput
import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.util.ImageSize
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Roundtrip do escritor OPZ (task 6.1 / D6, risco "escritor ZIP próprio"): escreve um `.opz`
 * STORED pura-Kotlin e reabre com o mesmo caminho da leitura em regime (Okio `openZip` via
 * [CbzArchive]) — lista, lê bytes e desserializa o `manifest.json`. Se o CRC/central directory
 * do escritor estivessem errados, o `openZip` falharia aqui.
 */
class OpzRoundtripJvmTest {

    private fun writeOpz(): String {
        val file = File.createTempFile("opentoons-opz", ".opz").apply { deleteOnExit() }
        val pages = listOf(
            OpzPageInput("001.jpg", "bytes-da-pagina-1".encodeToByteArray(), ImageSize(800, 1200)),
            OpzPageInput("002.jpg", "conteudo-maior-da-pagina-2".encodeToByteArray(), ImageSize(800, 1300)),
        )
        OpzWriter.write(
            fileSystem = FileSystem.SYSTEM,
            outputPath = file.absolutePath.toPath(),
            pages = pages,
            detectedLayout = Layout.PAGED,
            direction = ReadingDirection.LTR,
        )
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
    fun manifesto_carregaOrdemLayoutEDimensoes() {
        val path = writeOpz()
        val manifest = OpzReader.manifest(path)
        assertNotNull(manifest)
        assertEquals(Layout.PAGED.name, manifest.detectedLayout)
        assertEquals(ReadingDirection.LTR.name, manifest.direction)
        assertEquals(listOf("001.jpg", "002.jpg"), manifest.pages.map { it.name })
        assertEquals(800, manifest.pages[0].width)
        assertEquals(1200, manifest.pages[0].height)
        // campos ADR-0003 previstos e nulos neste marco
        assertEquals(null, manifest.obraId)
        assertEquals(null, manifest.chavePublicador)
    }

    @Test
    fun pageNames_seguemAOrdemDoManifesto() {
        val path = writeOpz()
        assertEquals(listOf("001.jpg", "002.jpg"), OpzReader.pageNames(path))
    }
}
