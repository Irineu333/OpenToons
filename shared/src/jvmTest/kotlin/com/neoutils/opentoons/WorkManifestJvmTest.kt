package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.work.CoverSource
import com.neoutils.opentoons.data.local.work.WorkCover
import com.neoutils.opentoons.data.local.work.WorkManifest
import com.neoutils.opentoons.data.local.work.WorkManifestStore
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Roundtrip do `work.json` (task 6.1 / D2): escreve o manifesto de obra via [WorkManifestStore]
 * e o relê, garantindo que os dados intrínsecos (título, direction detectada, identidade da
 * capa) sobrevivem em disco — a obra é auto-descritiva sem o banco.
 */
class WorkManifestJvmTest {

    private fun tempObraDir(): okio.Path {
        val dir = File(File.createTempFile("opentoons-obra", "").apply { delete() }, "obra").apply { mkdirs() }
        dir.deleteOnExit()
        return dir.absolutePath.toPath()
    }

    @Test
    fun workJson_roundtrip_preservaDados() {
        val obraDir = tempObraDir()
        val manifest = WorkManifest(
            obraId = "obra-1",
            title = "Minha Obra",
            description = "Uma sinopse editada no import",
            direction = "RTL",
            cover = WorkCover(chapterId = "cap-1", entryName = "001.jpg"),
        )
        WorkManifestStore.write(FileSystem.SYSTEM, obraDir, manifest)

        val read = WorkManifestStore.read(FileSystem.SYSTEM, obraDir)
        assertNotNull(read)
        assertEquals("obra-1", read.obraId)
        assertEquals("Minha Obra", read.title)
        assertEquals("Uma sinopse editada no import", read.description)
        assertEquals("RTL", read.direction)
        assertEquals("cap-1", read.cover?.chapterId)
        assertEquals("001.jpg", read.cover?.entryName)
        // campo assinado do ADR-0003 previsto e nulo neste marco
        assertNull(read.chavePublicador)
    }

    @Test
    fun read_semArquivo_retornaNull() {
        assertNull(WorkManifestStore.read(FileSystem.SYSTEM, tempObraDir()))
    }

    @Test
    fun read_manifestoAntigoSemDescription_desserializaVazio() {
        // Forward-compat (edit-import-metadata): um work.json anterior ao campo `description`
        // deve reler com description vazia (default), sem falhar.
        val obraDir = tempObraDir()
        val legacyJson = """
            {"version":1,"obraId":"obra-legado","title":"Obra Legada","direction":"LTR"}
        """.trimIndent()
        FileSystem.SYSTEM.write(WorkManifestStore.pathIn(obraDir)) { write(legacyJson.encodeToByteArray()) }

        val read = WorkManifestStore.read(FileSystem.SYSTEM, obraDir)
        assertNotNull(read)
        assertEquals("Obra Legada", read.title)
        assertEquals("", read.description)
    }

    @Test
    fun read_capaAntigaSemSource_desserializaComoPagina() {
        // Forward-compat (improve-import): um work.json cuja `cover` é o par antigo
        // {chapterId, entryName} sem `source` deve reler como capa de PÁGINA (default).
        val obraDir = tempObraDir()
        val legacyJson = """
            {"version":1,"obraId":"o","title":"T","direction":"LTR",
             "cover":{"chapterId":"cap-1","entryName":"001.jpg"}}
        """.trimIndent()
        FileSystem.SYSTEM.write(WorkManifestStore.pathIn(obraDir)) { write(legacyJson.encodeToByteArray()) }

        val read = WorkManifestStore.read(FileSystem.SYSTEM, obraDir)
        assertNotNull(read)
        assertEquals(CoverSource.PAGE, read.cover?.source)
        assertEquals("cap-1", read.cover?.chapterId)
        assertEquals("001.jpg", read.cover?.entryName)
    }

    @Test
    fun capaExterna_roundtrip_semReferenciaDePagina() {
        val obraDir = tempObraDir()
        WorkManifestStore.write(
            FileSystem.SYSTEM,
            obraDir,
            WorkManifest(
                obraId = "obra-2",
                title = "Capa Externa",
                direction = "LTR",
                cover = WorkCover.external(),
            ),
        )

        val read = WorkManifestStore.read(FileSystem.SYSTEM, obraDir)
        assertNotNull(read)
        // A capa é autônoma: proveniência externa não carrega {chapterId, entryName}.
        assertEquals(CoverSource.EXTERNAL, read.cover?.source)
        assertNull(read.cover?.chapterId)
        assertNull(read.cover?.entryName)
    }
}
