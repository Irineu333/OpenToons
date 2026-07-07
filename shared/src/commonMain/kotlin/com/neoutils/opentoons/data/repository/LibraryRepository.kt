package com.neoutils.opentoons.data.repository

import com.neoutils.opentoons.data.db.ChapterDao
import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.ProgressDao
import com.neoutils.opentoons.data.db.ProgressEntity
import com.neoutils.opentoons.data.db.WorkDao
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.data.local.work.CoverStore
import com.neoutils.opentoons.data.local.work.WorkManifestStore
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.ChapterProgress
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.util.NaturalOrder
import com.neoutils.opentoons.util.ioDispatcher
import com.neoutils.opentoons.util.nowMillis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * Repositório da biblioteca offline: única porta para obras/capítulos/progresso (Room, D8).
 * Compõe o modelo de domínio a partir das entidades e resolve a marca de "lido" juntando o
 * progresso ao capítulo.
 */
class LibraryRepository(
    private val workDao: WorkDao,
    private val chapterDao: ChapterDao,
    private val progressDao: ProgressDao,
) {

    fun observeLibrary(): Flow<List<Work>> =
        workDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeWork(uuid: String): Flow<Work?> =
        workDao.observe(uuid).map { it?.toDomain() }

    suspend fun work(uuid: String): Work? = workDao.find(uuid)?.toDomain()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeChapters(workUuid: String): Flow<List<Chapter>> =
        chapterDao.observeForWork(workUuid).flatMapLatest { chapters ->
            progressDao.observeForChapters(chapters.map { it.id }).map { progress ->
                val readIds = progress.filter { it.completed }.map { it.chapterId }.toSet()
                chapters.map { it.toDomain(read = it.id in readIds) }
            }
        }

    suspend fun chapter(id: String): Chapter? =
        chapterDao.find(id)?.let { entity ->
            entity.toDomain(read = progressDao.find(id)?.completed == true)
        }

    fun observeProgress(chapterId: String): Flow<ChapterProgress?> =
        progressDao.observe(chapterId).map { it?.toDomain() }

    suspend fun progress(chapterId: String): ChapterProgress? =
        progressDao.find(chapterId)?.toDomain()

    suspend fun saveProgress(progress: ChapterProgress) {
        progressDao.upsert(
            ProgressEntity(
                chapterId = progress.chapterId,
                pageIndex = progress.pageIndex,
                scrollFraction = progress.scrollFraction,
                completed = progress.completed,
                updatedAt = progress.updatedAt,
            ),
        )
    }

    suspend fun toggleFavorite(uuid: String) {
        val current = workDao.find(uuid) ?: return
        workDao.setFavorite(uuid, !current.favorite)
    }

    suspend fun setWorkLayoutOverride(uuid: String, layout: Layout?) =
        workDao.setLayoutOverride(uuid, layout?.name)

    suspend fun setWorkDirection(uuid: String, direction: ReadingDirection) =
        workDao.setDirectionOverride(uuid, direction.name)

    suspend fun setChapterLayoutOverride(chapterId: String, layout: Layout?) =
        chapterDao.setLayoutOverride(chapterId, layout?.name)

    /**
     * Remove a obra por completo: apaga a **pasta própria da obra** (`obras/{obra}/` com todos
     * os `.opz`, D2/task 5.5), o progresso dos capítulos e as linhas do banco (capítulos caem
     * por cascata da FK da obra).
     */
    suspend fun deleteWork(uuid: String) = withContext(ioDispatcher) {
        val chapters = chapterDao.listForWork(uuid)
        // A pasta da obra é o pai comum dos `.opz` dos capítulos (obras/{obra}/).
        val obraDir = chapters.firstOrNull()?.archivePath?.toPath()?.parent
        chapters.forEach { chapter ->
            runCatching { FileSystem.SYSTEM.delete(chapter.archivePath.toPath(), mustExist = false) }
        }
        obraDir?.let { dir ->
            runCatching { FileSystem.SYSTEM.deleteRecursively(dir, mustExist = false) }
        }
        progressDao.deleteForChapters(chapters.map { it.id })
        workDao.delete(uuid)
    }

    /**
     * Remove os capítulos selecionados (task 5.3/5.4): apaga os `.opz` do disco e o progresso,
     * exclui as linhas e **reindexa** o `orderIndex` dos capítulos restantes. Retorna quantos
     * capítulos sobraram na obra (0 = obra ficou vazia — o chamador decide o que fazer).
     */
    suspend fun deleteChapters(workUuid: String, chapterIds: Set<String>): Int =
        withContext(ioDispatcher) {
            if (chapterIds.isEmpty()) return@withContext chapterDao.listForWork(workUuid).size
            val all = chapterDao.listForWork(workUuid)
            all.filter { it.id in chapterIds }.forEach { chapter ->
                runCatching {
                    FileSystem.SYSTEM.delete(chapter.archivePath.toPath(), mustExist = false)
                }
            }
            progressDao.deleteForChapters(chapterIds.toList())
            chapterDao.deleteByIds(chapterIds.toList())

            // Reindexa os remanescentes para manter orderIndex contíguo (0..n-1).
            val remaining = all.filter { it.id !in chapterIds }
            remaining.forEachIndexed { index, chapter ->
                if (chapter.orderIndex != index) {
                    chapterDao.setOrderIndex(chapter.id, index)
                }
            }
            // A capa **não** é regenerada na remoção (D5: gerada uma vez, no import). A
            // `cover.webp` é cache derivado — permanece válida; se a obra ficar vazia, o
            // chamador remove a obra (deleteWork apaga a pasta inteira, capa incluída).
            remaining.size
        }

    /** Próximo índice de ordem para adicionar capítulo a uma obra existente. */
    suspend fun nextOrderIndex(workUuid: String): Int =
        (chapterDao.maxOrderIndex(workUuid) ?: -1) + 1

    /** Persiste a obra e seus capítulos importados (usado pelo importer). */
    suspend fun addWork(work: WorkEntity, chapters: List<ChapterEntity>) {
        workDao.upsert(work)
        chapters.forEach { chapterDao.upsert(it) }
    }

    suspend fun addChapter(chapter: ChapterEntity) = chapterDao.upsert(chapter)

    /**
     * Reconstrói a biblioteca a partir do disco (D6, tasks 5.1–5.3): varre `obras/{id}/work.json`
     * e recria `WorkEntity`/`ChapterEntity` a partir dele e dos `.opz`. O **disco vence** —
     * `work.json` é a fonte de verdade do dado (título, direction detectada, capa). O **estado
     * pessoal** (favorito, `directionOverride`, `layoutOverride`, progresso/lido) é **preservado**
     * casando por `obraId` (obra) e `chapterId` (capítulo, no `manifest.json` do `.opz`).
     *
     * A ordem dos capítulos é o *natural sort* dos nomes dos `.opz` (mesma regra do import). O
     * progresso não é tocado (é casado por `chapterId`, que os `.opz` preservam).
     */
    suspend fun rescanFromDisk(obrasRoot: Path) = withContext(ioDispatcher) {
        val fs = FileSystem.SYSTEM
        if (fs.metadataOrNull(obrasRoot)?.isDirectory != true) return@withContext

        for (obraDir in fs.list(obrasRoot)) {
            if (fs.metadataOrNull(obraDir)?.isDirectory != true) continue
            val manifest = WorkManifestStore.read(fs, obraDir) ?: continue // sem work.json → não é obra
            val obraId = manifest.obraId

            // Estado pessoal preexistente (disco vence no dado; estado é preservado).
            val prior = workDao.find(obraId)
            val coverPath = CoverStore.pathIn(obraDir).takeIf { fs.exists(it) }?.toString()
            workDao.upsert(
                WorkEntity(
                    uuid = obraId,
                    publisherKey = manifest.chavePublicador,
                    title = manifest.title,
                    coverPath = coverPath,
                    direction = manifest.direction,
                    directionOverride = prior?.directionOverride,
                    layoutOverride = prior?.layoutOverride,
                    favorite = prior?.favorite ?: false,
                    createdAt = prior?.createdAt ?: nowMillis(),
                ),
            )

            // Capítulos: um por `.opz`, ordem = natural sort dos nomes; id = `chapterId` interno.
            val priorChapters = chapterDao.listForWork(obraId).associateBy { it.id }
            val opzPaths = fs.list(obraDir)
                .filter { it.name.endsWith(".opz") }
                .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            opzPaths.forEachIndexed { index, opzPath ->
                val opz = opzPath.toString()
                val cm = OpzReader.manifest(opz)
                val chapterId = cm?.chapterId ?: return@forEachIndexed // sem id estável → pula
                val priorChapter = priorChapters[chapterId]
                chapterDao.upsert(
                    ChapterEntity(
                        id = chapterId,
                        workUuid = obraId,
                        title = opzPath.name.removeSuffix(".opz"),
                        archivePath = opz,
                        orderIndex = index,
                        pageCount = cm.pages.size,
                        sourceKey = priorChapter?.sourceKey ?: LocalImportSource.KEY,
                        detectedLayout = cm.detectedLayout,
                        layoutOverride = priorChapter?.layoutOverride,
                    ),
                )
            }
        }
    }
}
