package com.neoutils.opentoons.data.importer

import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.domain.model.WorkId
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.util.ImageSize
import com.neoutils.opentoons.util.LayoutHeuristic
import com.neoutils.opentoons.util.ioDispatcher
import com.neoutils.opentoons.util.nowMillis
import com.neoutils.opentoons.util.readImageSize
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.withContext
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Importação local (spec content-import, tasks 3.3–3.6):
 *  1. **copy-in** dos bytes do arquivo escolhido para o storage próprio (`FileKit.filesDir`),
 *     tornando o app dono do `.cbz` — imune a mover/apagar a origem (D4);
 *  2. indexação das páginas via Okio `openZip` (ordenação natural — D5/task 3.5);
 *  3. **heurística de layout** por amostra de aspect-ratio (task 5.1);
 *  4. metadados básicos (título do nome do arquivo, capa = 1ª página) e montagem da obra.
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

        // (1) copy-in: bytes → storage próprio, mantendo o `.cbz` intacto.
        val destination = PlatformFile(FileKit.filesDir, "$workUuid.cbz")
        destination.write(picked.readBytes())
        val archivePath = destination.path

        // (2) indexação sob demanda (só nomes; sem carregar imagens).
        val entries = CbzArchive.listImageEntries(archivePath)

        // (3) heurística: amostra de tamanhos → detectedLayout.
        val detected = LayoutHeuristic.detectFromSizes(sampleSizes(archivePath, entries))

        // (4) metadados: título do nome do arquivo; capa = primeira entrada.
        val title = picked.name.substringBeforeLast('.').ifBlank { "Sem título" }
        val cover = entries.firstOrNull()

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
        val chapter = ChapterEntity(
            id = Uuid.random().toString(),
            workUuid = workUuid,
            title = title,
            archivePath = archivePath,
            orderIndex = 0,
            pageCount = entries.size,
            sourceKey = LocalImportSource.KEY,
            detectedLayout = detected.name,
            layoutOverride = null,
        )
        repository.addWorkWithChapter(work, chapter)

        Work(
            id = WorkId(workUuid),
            title = title,
            coverArchivePath = work.coverArchivePath,
            coverEntryName = work.coverEntryName,
            direction = ReadingDirection.LTR,
            layoutOverride = null,
            favorite = false,
        )
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
