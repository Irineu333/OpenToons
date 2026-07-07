package com.neoutils.opentoons.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Obra. `uuid` é a identidade estável (ADR-0003); `publisherKey` prevê o par
 * `(chave_publicador, uuid)` mas fica **nulo** no Marco 1 — sem publicador atribuível e
 * sem evento de leitura (ADR-0009, task 4.4).
 *
 * Campos de layout/direção (task 4.2): `direction` é da obra (RTL/LTR, só paginado);
 * `layoutOverride` é o override no nível da obra (a detecção mora no capítulo).
 */
@Entity(tableName = "works")
data class WorkEntity(
    @PrimaryKey val uuid: String,
    val publisherKey: String?,
    val title: String,
    val coverArchivePath: String?,
    val coverEntryName: String?,
    val direction: String,
    val layoutOverride: String?,
    val favorite: Boolean,
    val createdAt: Long,
)

/**
 * Capítulo: unidade endereçável, aponta o **`.opz` do próprio capítulo**
 * (`obras/{obra}/{capítulo}.opz`, D2). Cada OPZ é plano — `entryDir` foi aposentado (D1/D2).
 * `detectedLayout` vem da heurística no import e é guardado **separado** de `layoutOverride`.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = WorkEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["workUuid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("workUuid")],
)
data class ChapterEntity(
    @PrimaryKey val id: String,
    val workUuid: String,
    val title: String,
    // Caminho do `.opz` deste capítulo no storage próprio (`obras/{obra}/{capítulo}.opz`).
    val archivePath: String,
    val orderIndex: Int,
    val pageCount: Int,
    val sourceKey: String,
    val detectedLayout: String,
    val layoutOverride: String?,
)

/**
 * Progresso por capítulo (task 4.3): `pageIndex` (paginado) e `scrollFraction` (long strip);
 * `completed` é a marca de "lido". Retomar leitura e refletir lido no detalhe saem daqui.
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val chapterId: String,
    val pageIndex: Int,
    val scrollFraction: Float,
    val completed: Boolean,
    val updatedAt: Long,
)
