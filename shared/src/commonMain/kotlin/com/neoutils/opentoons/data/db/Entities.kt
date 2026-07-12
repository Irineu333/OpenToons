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
 * Split estado × dado (D6): o banco é **índice reconstruível**, não dono. Dado (título,
 * `direction` detectada, capa) vem do `work.json`; estado (favorito, `directionOverride`,
 * `layoutOverride`, `createdAt` do import) é do banco. `coverPath` aponta a `cover.webp`
 * derivada (cache) — conveniência da grade, também reconstruível do disco.
 */
@Entity(tableName = "works")
data class WorkEntity(
    @PrimaryKey val uuid: String,
    val publisherKey: String?,
    val title: String,
    // Descrição editável no import (dado do work.json; vazia por default). Índice do disco.
    val description: String,
    val coverPath: String?,
    val direction: String,
    val directionOverride: String?,
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
 * Progresso por capítulo. No paginado é `pageIndex`; no long strip é a posição independente de
 * layout `(pageIndex, fractionWithinPage)` (design D4, task 4.1). `scrollFraction` é legado
 * (fração de rolagem da altura total), preservado para conversão aproximada do progresso antigo
 * (task 4.2). `completed` é a marca de "lido". A coluna `fractionWithinPage` foi adicionada na
 * migração v5→v6 (ver `Migrations.kt`).
 */
@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val chapterId: String,
    val pageIndex: Int,
    val fractionWithinPage: Float,
    val scrollFraction: Float,
    val completed: Boolean,
    val updatedAt: Long,
)
