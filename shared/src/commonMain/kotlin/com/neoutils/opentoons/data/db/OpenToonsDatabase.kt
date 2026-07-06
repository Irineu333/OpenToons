package com.neoutils.opentoons.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.neoutils.opentoons.util.ioDispatcher

@Database(
    entities = [WorkEntity::class, ChapterEntity::class, ProgressEntity::class],
    // v2: capítulos por pasta dentro do CBZ (campo `entryDir`). Sem dados a preservar no
    // Marco 1 → recriação destrutiva (ver `buildDatabase`).
    version = 2,
    exportSchema = true,
)
@ConstructedBy(OpenToonsDatabaseConstructor::class)
abstract class OpenToonsDatabase : RoomDatabase() {
    abstract fun workDao(): WorkDao
    abstract fun chapterDao(): ChapterDao
    abstract fun progressDao(): ProgressDao
}

// O actual deste `expect object` é gerado pelo KSP do Room em cada alvo (task 4.x / D8).
@Suppress("NO_ACTUAL_FOR_EXPECT", "KotlinNoActualForExpect")
expect object OpenToonsDatabaseConstructor : RoomDatabaseConstructor<OpenToonsDatabase> {
    override fun initialize(): OpenToonsDatabase
}

/**
 * Finaliza um builder com o `BundledSQLiteDriver` (obrigatório p/ iOS/Desktop, montado desde
 * o dia 1 — D8) e o contexto de coroutine de IO. Cada plataforma provê o builder base.
 */
fun buildDatabase(builder: RoomDatabase.Builder<OpenToonsDatabase>): OpenToonsDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(ioDispatcher)
        // Marco 1 não tem dados a preservar: recria em qualquer divergência de schema
        // (inclui o caso do arquivo pré-existir vazio ao abrir com o BundledSQLiteDriver).
        .fallbackToDestructiveMigration(dropAllTables = true)
        .build()
