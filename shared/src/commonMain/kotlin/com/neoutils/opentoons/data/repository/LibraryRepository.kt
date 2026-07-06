package com.neoutils.opentoons.data.repository

import com.neoutils.opentoons.data.db.ChapterDao
import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.ProgressDao
import com.neoutils.opentoons.data.db.ProgressEntity
import com.neoutils.opentoons.data.db.WorkDao
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.ChapterProgress
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

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

    suspend fun deleteWork(uuid: String) = workDao.delete(uuid)

    /** Próximo índice de ordem para adicionar capítulo a uma obra existente. */
    suspend fun nextOrderIndex(workUuid: String): Int =
        (chapterDao.maxOrderIndex(workUuid) ?: -1) + 1

    /** Persiste obra + capítulo importados (usado pelo importer). */
    suspend fun addWorkWithChapter(work: WorkEntity, chapter: ChapterEntity) {
        workDao.upsert(work)
        chapterDao.upsert(chapter)
    }

    suspend fun addChapter(chapter: ChapterEntity) = chapterDao.upsert(chapter)
}
