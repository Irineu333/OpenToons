package com.neoutils.opentoons.domain.model

/**
 * Obra da biblioteca. A capa exibida vem da **thumbnail de obra** (`cover.webp`, D5): a grade
 * lê um arquivo pequeno em vez de destrinchar um `.opz` de capítulo por célula. `direction` é
 * a direção **detectada** (dado, do `work.json`); `directionOverride` é a preferência do
 * usuário (estado, do banco) — a efetiva é o override, senão a detectada.
 */
data class Work(
    val id: WorkId,
    val title: String,
    val description: String = "",
    val coverPath: String?,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val directionOverride: ReadingDirection? = null,
    val layoutOverride: Layout? = null,
    val favorite: Boolean = false,
) {
    /** Chave do Coil para a `cover.webp` da obra (ver [CoverImage]). */
    val cover: CoverImage?
        get() = coverPath?.let { CoverImage(it) }

    /** Direção efetiva de leitura: override do usuário (estado) tem precedência sobre a detectada. */
    val effectiveDirection: ReadingDirection
        get() = directionOverride ?: direction
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

/**
 * Página dentro de um capítulo, na ordem natural das entradas (spec content-import). As
 * dimensões nativas (`width`/`height`, em px) vêm do sniff de header no open do capítulo
 * (design D1, task 1.6) e são **dado** — a base da geometria do long strip (tiles de altura
 * fixa). `0` significa desconhecida (formato não coberto pelo sniff): o layout cai num
 * fallback de proporção típica.
 */
data class Page(
    val index: Int,
    val entryName: String,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * Progresso persistido por capítulo (spec reading-experience / offline-library). No paginado é
 * `pageIndex`; no long strip passa a ser **posição independente de layout** (design D4):
 * `(pageIndex, fractionWithinPage)`, estável a rotação e troca de dispositivo — reconstruir px
 * a partir do par é trivial em qualquer largura. `scrollFraction` é **legado** (a fração de
 * rolagem sobre a altura total, que mudava de significado com a largura); mantido só para
 * converter progresso antigo por aproximação (task 4.2).
 */
data class ChapterProgress(
    val chapterId: String,
    val pageIndex: Int = 0,
    val fractionWithinPage: Float = 0f,
    val scrollFraction: Float = 0f,
    val completed: Boolean = false,
    val updatedAt: Long = 0L,
)

/** Referência a uma imagem dentro de um arquivo `.cbz`, usada como chave de load do Coil. */
data class ArchiveImage(
    val archivePath: String,
    val entryName: String,
)

/**
 * Referência à **capa de obra** (`cover.webp`) em disco, usada como chave de load do Coil
 * (grade/detalhe). É um arquivo pequeno derivado — carregar não abre nenhum `.opz` (D5).
 */
data class CoverImage(
    val path: String,
)

/**
 * Thumbnail **em memória** (bytes já codificados), usada como chave de load do Coil na revisão
 * de import (edit-import-metadata): a galeria de capa mostra candidatas **sem gravar em disco**
 * (a origem só é materializada ao confirmar). [key] é a chave estável de cache (o `chapterId`).
 */
data class ThumbnailImage(
    val key: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is ThumbnailImage && other.key == key
    override fun hashCode(): Int = key.hashCode()
}
