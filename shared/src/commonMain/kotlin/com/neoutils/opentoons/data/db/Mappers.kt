package com.neoutils.opentoons.data.db

import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.ChapterProgress
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.domain.model.WorkId

/** Mapeamentos entidade Room ↔ modelo de domínio. */

fun WorkEntity.toDomain(): Work = Work(
    id = WorkId(uuid = uuid, publisherKey = publisherKey),
    title = title,
    description = description,
    coverPath = coverPath,
    direction = runCatching { ReadingDirection.valueOf(direction) }.getOrDefault(ReadingDirection.LTR),
    directionOverride = directionOverride?.let { runCatching { ReadingDirection.valueOf(it) }.getOrNull() },
    layoutOverride = layoutOverride?.let { runCatching { Layout.valueOf(it) }.getOrNull() },
    favorite = favorite,
)

fun ChapterEntity.toDomain(read: Boolean): Chapter = Chapter(
    id = id,
    workUuid = workUuid,
    title = title,
    archivePath = archivePath,
    orderIndex = orderIndex,
    pageCount = pageCount,
    detectedLayout = runCatching { Layout.valueOf(detectedLayout) }.getOrDefault(Layout.PAGED),
    layoutOverride = layoutOverride?.let { runCatching { Layout.valueOf(it) }.getOrNull() },
    sourceKey = sourceKey,
    read = read,
)

fun ProgressEntity.toDomain(): ChapterProgress = ChapterProgress(
    chapterId = chapterId,
    pageIndex = pageIndex,
    scrollFraction = scrollFraction,
    completed = completed,
    updatedAt = updatedAt,
)
