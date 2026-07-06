package com.neoutils.opentoons.data.importer

import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.util.ImageSize
import com.neoutils.opentoons.util.LayoutHeuristic
import com.neoutils.opentoons.util.ioDispatcher
import com.neoutils.opentoons.util.nowMillis
import com.neoutils.opentoons.util.readImageSize
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.copyTo
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Importação local (spec content-import, tasks 3.3–3.6):
 *  1. **copy-in** streaming dos bytes para o storage próprio (`FileKit.filesDir`) — app dono
 *     do `.cbz` intacto (D4);
 *  2. detecção de **capítulos por pasta** dentro do arquivo (cada diretório com imagens vira
 *     um capítulo; CBZ "plano" = um capítulo só) via Okio `openZip` (D5);
 *  3. **heurística de layout** por capítulo (amostra de aspect-ratio — task 5.1);
 *  4. metadados básicos (título do nome do arquivo, capa = 1ª página do 1º capítulo).
 *
 * A obra recebe `uuid` estável e `chave_publicador` NULA (ADR-0003/0009) — sem evento de leitura.
 */
class CbzImporter(
    private val repository: LibraryRepository,
    private val sampleSize: Int = 8,
) {

    @OptIn(ExperimentalUuidApi::class)
    suspend fun importFrom(picked: PlatformFile): Work = withContext(ioDispatcher) {
        val workUuid = Uuid.random().toString()

        // (1) copy-in streaming (não carrega o arquivo inteiro em memória).
        val destination = PlatformFile(FileKit.filesDir, "$workUuid.cbz")
        picked.copyTo(destination)
        val archivePath = destination.path

        // (2) capítulos por pasta.
        val archiveChapters = CbzArchive.chapters(archivePath)
        val title = picked.name.substringBeforeLast('.').ifBlank { "Sem título" }
        val cover = archiveChapters.firstOrNull()?.entries?.firstOrNull()

        val work = WorkEntity(
            uuid = workUuid,
            publisherKey = null, // Marco 1: sem publicador atribuível (ADR-0009)
            title = title,
            coverArchivePath = cover?.let { archivePath },
            coverEntryName = cover,
            direction = ReadingDirection.LTR.name,
            layoutOverride = null,
            favorite = false,
            createdAt = nowMillis(),
        )

        // (3)+(4) um capítulo por grupo, com layout detectado a partir das imagens do grupo.
        val chapters = archiveChapters.mapIndexed { index, chapter ->
            val detected = LayoutHeuristic.detectFromSizes(sampleSizes(archivePath, chapter.entries))
            ChapterEntity(
                id = Uuid.random().toString(),
                workUuid = workUuid,
                title = chapterTitle(chapter.dir, index, archiveChapters.size),
                archivePath = archivePath,
                entryDir = chapter.dir,
                orderIndex = index,
                pageCount = chapter.entries.size,
                sourceKey = LocalImportSource.KEY,
                detectedLayout = detected.name,
                layoutOverride = null,
            )
        }

        repository.addWork(work, chapters)
        work.toDomain()
    }

    /** Título do capítulo: nome da pasta quando houver, senão "Capítulo N". */
    private fun chapterTitle(dir: String, index: Int, total: Int): String {
        val leaf = dir.substringAfterLast('/').trim()
        return when {
            leaf.isNotBlank() -> leaf
            total <= 1 -> "Capítulo 1"
            else -> "Capítulo ${index + 1}"
        }
    }

    /** Lê os tamanhos de até [sampleSize] páginas espaçadas para alimentar a heurística. */
    private fun sampleSizes(archivePath: String, entries: List<String>): List<ImageSize> {
        if (entries.isEmpty()) return emptyList()
        val step = maxOf(1, entries.size / sampleSize)
        return entries.filterIndexed { index, _ -> index % step == 0 }
            .take(sampleSize)
            .mapNotNull { entry -> readImageSize(CbzArchive.readEntry(archivePath, entry)) }
    }
}
