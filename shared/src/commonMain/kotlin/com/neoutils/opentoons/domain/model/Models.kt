package com.neoutils.opentoons.domain.model

/**
 * Obra da biblioteca. A capa é endereçada por (arquivo do capítulo, entrada) e carregada
 * sob demanda pelo Coil a partir do `openZip` — não extraímos a imagem no import (D4/D5).
 */
data class Work(
    val id: WorkId,
    val title: String,
    val coverArchivePath: String?,
    val coverEntryName: String?,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val layoutOverride: Layout? = null,
    val favorite: Boolean = false,
) {
    /** Chave do Coil para a capa (ver [ArchiveImage]). */
    val cover: ArchiveImage?
        get() = if (coverArchivePath != null && coverEntryName != null)
            ArchiveImage(coverArchivePath, coverEntryName) else null
}

/**
 * Capítulo: unidade endereçável (≈ bloco/CID no futuro). `archivePath` aponta o **`.opz`
 * do próprio capítulo** (`obras/{obra}/{capítulo}.opz`, D2) — cada OPZ é plano, então
 * `entryDir` foi aposentado (D1/D2). `detectedLayout` vem da heurística no import (D6) e é
 * guardado separado de `layoutOverride`. `sourceKey` seleciona a `Source` que serve as
 * páginas — "local" no Marco 1, "network" no Marco 2 (seam da spec content-import).
 */
data class Chapter(
    val id: String,
    val workUuid: String,
    val title: String,
    val archivePath: String,
    val orderIndex: Int,
    val pageCount: Int,
    val detectedLayout: Layout,
    val layoutOverride: Layout? = null,
    val sourceKey: String = "local",
    val read: Boolean = false,
)

/** Página dentro de um capítulo, na ordem natural das entradas (spec content-import). */
data class Page(
    val index: Int,
    val entryName: String,
)

/**
 * Progresso persistido por capítulo (spec reading-experience / offline-library):
 * `pageIndex` no paginado, `scrollFraction` no long strip.
 */
data class ChapterProgress(
    val chapterId: String,
    val pageIndex: Int = 0,
    val scrollFraction: Float = 0f,
    val completed: Boolean = false,
    val updatedAt: Long = 0L,
)

/** Referência a uma imagem dentro de um arquivo `.cbz`, usada como chave de load do Coil. */
data class ArchiveImage(
    val archivePath: String,
    val entryName: String,
)
