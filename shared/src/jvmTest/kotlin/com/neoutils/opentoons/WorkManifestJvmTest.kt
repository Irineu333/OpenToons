package com.neoutils.opentoons

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
            direction = "RTL",
            cover = WorkCover(chapterId = "cap-1", entryName = "001.jpg"),
        )
        WorkManifestStore.write(FileSystem.SYSTEM, obraDir, manifest)

        val read = WorkManifestStore.read(FileSystem.SYSTEM, obraDir)
        assertNotNull(read)
        assertEquals("obra-1", read.obraId)
        assertEquals("Minha Obra", read.title)
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
}
