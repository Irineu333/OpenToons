package com.neoutils.opentoons.data.repository

import com.neoutils.opentoons.data.db.ChapterDao
import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.ProgressDao
import com.neoutils.opentoons.data.db.ProgressEntity
import com.neoutils.opentoons.data.db.WorkDao
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.ChapterProgress
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.util.ioDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okio.FileSystem
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
        workDao.setDirection(uuid, direction.name)

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

            // Capa órfã (task 5.4): se a capa apontava um `.opz` removido, re-aponta para o
            // primeiro capítulo restante (1ª página); sem capítulos, limpa a capa.
            val work = workDao.find(workUuid)
            val coverAlive = work?.coverArchivePath != null &&
                remaining.any { it.archivePath == work.coverArchivePath }
            if (work != null && !coverAlive) {
                val first = remaining.firstOrNull()
                val firstPage = first?.let { OpzReader.pageNames(it.archivePath).firstOrNull() }
                workDao.setCover(workUuid, first?.archivePath?.takeIf { firstPage != null }, firstPage)
            }
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
}
