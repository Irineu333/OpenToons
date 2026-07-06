package com.neoutils.opentoons.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** DAOs de biblioteca/detalhe/progresso (task 4.4). */

@Dao
interface WorkDao {
    @Upsert
    suspend fun upsert(work: WorkEntity)

    @Query("SELECT * FROM works ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WorkEntity>>

    @Query("SELECT * FROM works WHERE uuid = :uuid")
    fun observe(uuid: String): Flow<WorkEntity?>

    @Query("SELECT * FROM works WHERE uuid = :uuid")
    suspend fun find(uuid: String): WorkEntity?

    @Query("UPDATE works SET favorite = :favorite WHERE uuid = :uuid")
    suspend fun setFavorite(uuid: String, favorite: Boolean)

    @Query("UPDATE works SET layoutOverride = :layout WHERE uuid = :uuid")
    suspend fun setLayoutOverride(uuid: String, layout: String?)

    @Query("UPDATE works SET direction = :direction WHERE uuid = :uuid")
    suspend fun setDirection(uuid: String, direction: String)

    @Query("DELETE FROM works WHERE uuid = :uuid")
    suspend fun delete(uuid: String)
}

@Dao
interface ChapterDao {
    @Upsert
    suspend fun upsert(chapter: ChapterEntity)

    @Query("SELECT * FROM chapters WHERE workUuid = :workUuid ORDER BY orderIndex")
    fun observeForWork(workUuid: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun find(id: String): ChapterEntity?

    @Query("UPDATE chapters SET layoutOverride = :layout WHERE id = :id")
    suspend fun setLayoutOverride(id: String, layout: String?)

    @Query("SELECT MAX(orderIndex) FROM chapters WHERE workUuid = :workUuid")
    suspend fun maxOrderIndex(workUuid: String): Int?
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE chapterId = :chapterId")
    suspend fun find(chapterId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE chapterId = :chapterId")
    fun observe(chapterId: String): Flow<ProgressEntity?>

    @Query("SELECT * FROM progress WHERE chapterId IN (:chapterIds)")
    fun observeForChapters(chapterIds: List<String>): Flow<List<ProgressEntity>>
}
