package com.neoutils.opentoons

import androidx.room.Room
import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.OpenToonsDatabase
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.buildDatabase
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.data.local.work.WorkCover
import com.neoutils.opentoons.data.local.work.WorkManifest
import com.neoutils.opentoons.data.local.work.WorkManifestStore
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.domain.model.ChapterProgress
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reconstrução da biblioteca a partir do disco (task 6.2 / D6): monta uma obra em disco
 * (`work.json` + `.opz`), simula uma divergência com o índice do banco e chama
 * [LibraryRepository.rescanFromDisk]. Valida que o **disco vence** no dado (título/direction) e
 * que o **estado pessoal** (favorito, `directionOverride`, `layoutOverride`, progresso) é
 * **preservado** casando por `obraId`/`chapterId`.
 */
class RescanJvmTest {

    private val fs = FileSystem.SYSTEM

    private fun newDb(): OpenToonsDatabase {
        val dbFile = File.createTempFile("opentoons-rescan", ".db").apply { deleteOnExit() }
        return buildDatabase(Room.databaseBuilder<OpenToonsDatabase>(name = dbFile.absolutePath))
    }

    private fun tempObrasRoot(): Path {
        val dir = File(File.createTempFile("opentoons-obras", "").apply { delete() }, "obras").apply { mkdirs() }
        dir.deleteOnExit()
        return dir.absolutePath.toPath()
    }

    @Test
    fun rescan_discoVence_ePreservaEstadoPorId() = runBlocking {
        val db = newDb()
        val repo = LibraryRepository(db.workDao(), db.chapterDao(), db.progressDao())

        // Obra em disco: título/direction/capa são o DADO (fonte de verdade).
        val obrasRoot = tempObrasRoot()
        val obraId = "obra-1"
        val chapterId = "cap-1"
        val obraDir = obrasRoot / obraId
        val opz = obraDir / "Capítulo 1.opz"
        OpzWriter.write(fs, opz, chapterId = chapterId, obraId = obraId) { sink ->
            sink.page("001.jpg", "pagina".encodeToByteArray())
        }
        WorkManifestStore.write(
            fs, obraDir,
            WorkManifest(
                obraId = obraId, title = "Título do Disco", description = "Sinopse do Disco",
                direction = "RTL", cover = WorkCover.page(chapterId, "001.jpg"),
            ),
        )

        // Índice do banco DIVERGENTE + estado pessoal (favorito/overrides/progresso).
        repo.addWork(
            WorkEntity(
                uuid = obraId, publisherKey = null, title = "Título Velho do Banco",
                description = "Sinopse velha do banco",
                coverPath = null, direction = "LTR", directionOverride = ReadingDirection.RTL.name,
                layoutOverride = null, favorite = true, createdAt = 123L,
            ),
            listOf(
                ChapterEntity(
                    id = chapterId, workUuid = obraId, title = "Nome Velho",
                    archivePath = "/caminho/velho.opz", orderIndex = 0, pageCount = 0,
                    sourceKey = "local", detectedLayout = "PAGED", layoutOverride = Layout.LONG_STRIP.name,
                ),
            ),
        )
        repo.saveProgress(ChapterProgress(chapterId = chapterId, pageIndex = 7, completed = true, updatedAt = 99L))

        repo.rescanFromDisk(obrasRoot)

        // Disco vence no DADO (título, description e direction vêm do work.json).
        val work = repo.work(obraId)!!
        assertEquals("Título do Disco", work.title)
        assertEquals("Sinopse do Disco", work.description) // description propagada do work.json
        assertEquals(ReadingDirection.RTL, work.direction) // direction detectada = disco

        // Estado pessoal preservado por id.
        assertTrue(work.favorite)
        assertEquals(ReadingDirection.RTL, work.directionOverride)
        val chapter = repo.chapter(chapterId)!!
        assertEquals(Layout.LONG_STRIP, chapter.layoutOverride)
        assertEquals(opz.toString(), chapter.archivePath) // aponta o `.opz` real do disco
        assertEquals(1, chapter.pageCount) // reconstruído do manifesto
        val progress = repo.progress(chapterId)!!
        assertEquals(7, progress.pageIndex)
        assertTrue(progress.completed)
    }
}
