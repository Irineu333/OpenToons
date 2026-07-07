package com.neoutils.opentoons.data.importer

import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.data.local.ArchiveReader
import com.neoutils.opentoons.data.local.ContainerFormats
import com.neoutils.opentoons.data.local.opz.OpzPageInput
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.data.local.rar.UnsupportedFormatException
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.util.ImageSize
import com.neoutils.opentoons.util.LayoutHeuristic
import com.neoutils.opentoons.util.NaturalOrder
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
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Import unificado (spec content-import; tasks 4.1–4.5, 5.1). **Normaliza qualquer formato de
 * origem para OPZ por capítulo** (D1): decodifica CBZ/CBR/ZIP/RAR, materializa um `.opz`
 * STORED por capítulo em `obras/{obra}/{capítulo}.opz` (D2) e persiste as linhas.
 *
 * Grade 2×2 (D3): `cbz`/`cbr` são **unidade** (imagens → capítulos por pasta); `zip`/`rar`
 * são **pacote** (arquivos `.cbz`/`.cbr` internos, cada um um capítulo). A desambiguação é por
 * conteúdo: se as entradas de topo forem arquivos-arquivo, é pacote; se forem imagens, unidade.
 *
 * RAR vive só aqui (extract-all, D5); a leitura em regime é Okio-pura sobre OPZ.
 */
@OptIn(ExperimentalUuidApi::class)
class ContentImporter(
    private val repository: LibraryRepository,
    private val sampleSize: Int = 8,
) {

    /** Origem já normalizada em memória: capítulo com suas páginas (nome plano + bytes). */
    private class SourceChapter(val title: String, val pages: List<SourcePage>)
    private class SourcePage(val name: String, val bytes: ByteArray)

    private val storageRoot: Path get() = FileKit.filesDir.path.toPath()

    /** Importa uma **nova obra** a partir de CBZ/CBR/ZIP/RAR (pacotes permitidos). */
    suspend fun importWork(
        picked: PlatformFile,
        onProgress: (String) -> Unit = {},
    ): Work = withContext(ioDispatcher) {
        val workUuid = Uuid.random().toString()
        val title = picked.name.substringBeforeLast('.').ifBlank { "Sem título" }

        onProgress("Lendo arquivo…")
        val chapters = withTempSource(picked) { normalize(it, allowPackage = true) }
        require(chapters.isNotEmpty()) { "Nenhuma imagem encontrada no arquivo." }

        val entities = writeChaptersOpz(workUuid, chapters, baseOrder = 0, onProgress)
        val coverPage = chapters.first().pages.firstOrNull()?.name

        val work = WorkEntity(
            uuid = workUuid,
            publisherKey = null, // sem publicador atribuível (ADR-0009)
            title = title,
            coverArchivePath = coverPage?.let { entities.first().archivePath },
            coverEntryName = coverPage,
            direction = ReadingDirection.LTR.name,
            layoutOverride = null,
            favorite = false,
            createdAt = nowMillis(),
        )
        repository.addWork(work, entities)
        work.toDomain()
    }

    /**
     * Adiciona capítulos a uma **obra existente** a partir de arquivos-**unidade** (CBZ/CBR).
     * Pacotes (ZIP/RAR) são recusados (spec content-import). Retorna quantos capítulos entraram.
     */
    suspend fun addChapters(
        workUuid: String,
        picked: PlatformFile,
        onProgress: (String) -> Unit = {},
    ): Int = withContext(ioDispatcher) {
        onProgress("Lendo arquivo…")
        val chapters = withTempSource(picked) { normalize(it, allowPackage = false) }
        require(chapters.isNotEmpty()) { "Nenhuma imagem encontrada no arquivo." }

        val base = repository.nextOrderIndex(workUuid)
        val entities = writeChaptersOpz(workUuid, chapters, baseOrder = base, onProgress)
        entities.forEach { repository.addChapter(it) }
        entities.size
    }

    // --- Normalização (origem → capítulos em memória) ---

    private fun normalize(sourcePath: String, allowPackage: Boolean): List<SourceChapter> {
        val reader = ContainerFormats.open(sourcePath)
        val entries = reader.entryNames()
        val isPackage = entries.any { ArchiveReader.isArchive(it) }
        return if (isPackage) {
            if (!allowPackage) {
                throw UnsupportedFormatException(
                    "Pacotes (ZIP/RAR) não podem ser adicionados dentro de uma obra. " +
                        "Importe um arquivo CBZ/CBR (unidade).",
                )
            }
            packageChapters(reader, entries)
        } else {
            unitChapters(reader, entries)
        }
    }

    /** Unidade: imagens agrupadas por pasta — cada pasta um capítulo (raiz = um capítulo só). */
    private fun unitChapters(reader: ArchiveReader, entries: List<String>): List<SourceChapter> {
        val images = entries.filter { ArchiveReader.isImage(it) }.sortedWith(NaturalOrder)
        if (images.isEmpty()) return emptyList()
        val grouped = images
            .groupBy { it.substringBeforeLast('/', "") }
            .map { (dir, es) -> dir to es.sortedWith(NaturalOrder) }
            .sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }
        return grouped.mapIndexed { index, (dir, es) ->
            SourceChapter(
                title = chapterTitle(dir, index, grouped.size),
                pages = es.map { SourcePage(basename(it), reader.read(it)) },
            )
        }
    }

    /** Pacote: cada arquivo interno (`.cbz`/`.cbr`) vira um capítulo (imagens achatadas). */
    private fun packageChapters(reader: ArchiveReader, entries: List<String>): List<SourceChapter> {
        val inner = entries.filter { ArchiveReader.isArchive(it) }.sortedWith(NaturalOrder)
        return inner.mapNotNull { innerName ->
            val bytes = reader.read(innerName)
            val ext = innerName.substringAfterLast('.', "cbz")
            val pages = withTempBytes(bytes, ext) { innerPath ->
                val innerReader = ContainerFormats.open(innerPath)
                innerReader.entryNames()
                    .filter { ArchiveReader.isImage(it) }
                    .sortedWith(NaturalOrder)
                    .map { SourcePage(basename(it), innerReader.read(it)) }
            }
            if (pages.isEmpty()) null
            else SourceChapter(basename(innerName).substringBeforeLast('.'), pages)
        }
    }

    // --- Escrita OPZ + entidades ---

    private fun writeChaptersOpz(
        workUuid: String,
        chapters: List<SourceChapter>,
        baseOrder: Int,
        onProgress: (String) -> Unit,
    ): List<ChapterEntity> {
        val obraDir = storageRoot / "obras" / workUuid
        FileSystem.SYSTEM.createDirectories(obraDir)
        return chapters.mapIndexed { index, chapter ->
            onProgress("Convertendo capítulo ${index + 1}/${chapters.size}…")
            val chapterId = Uuid.random().toString()
            val opzPath = obraDir / "$chapterId.opz"
            val inputs = chapter.pages.map {
                OpzPageInput(it.name, it.bytes, readImageSize(it.bytes))
            }
            val detected = LayoutHeuristic.detectFromSizes(sampledSizes(inputs))
            OpzWriter.write(FileSystem.SYSTEM, opzPath, inputs, detected, ReadingDirection.LTR)
            ChapterEntity(
                id = chapterId,
                workUuid = workUuid,
                title = chapter.title,
                archivePath = opzPath.toString(),
                orderIndex = baseOrder + index,
                pageCount = chapter.pages.size,
                sourceKey = LocalImportSource.KEY,
                detectedLayout = detected.name,
                layoutOverride = null,
            )
        }
    }

    /** Amostra até [sampleSize] dimensões (já calculadas) espaçadas para a heurística de layout. */
    private fun sampledSizes(inputs: List<OpzPageInput>): List<ImageSize> {
        val sizes = inputs.mapNotNull { it.size }
        if (sizes.isEmpty()) return emptyList()
        val step = maxOf(1, sizes.size / sampleSize)
        return sizes.filterIndexed { i, _ -> i % step == 0 }.take(sampleSize)
    }

    private fun chapterTitle(dir: String, index: Int, total: Int): String {
        val leaf = dir.substringAfterLast('/').trim()
        return when {
            leaf.isNotBlank() -> leaf
            total <= 1 -> "Capítulo 1"
            else -> "Capítulo ${index + 1}"
        }
    }

    private fun basename(name: String): String = name.substringAfterLast('/')

    // --- Arquivos temporários (o picker pode ser uma URI; RAR/ZIP precisam de um path real) ---

    private suspend fun <T> withTempSource(picked: PlatformFile, block: (String) -> T): T {
        val ext = picked.name.substringAfterLast('.', "").ifBlank { "zip" }
        val tmp = PlatformFile(FileKit.filesDir, "import-tmp-${Uuid.random()}.$ext")
        picked.copyTo(tmp)
        val tmpPath = tmp.path
        return try {
            block(tmpPath)
        } finally {
            runCatching { FileSystem.SYSTEM.delete(tmpPath.toPath(), mustExist = false) }
        }
    }

    private fun <T> withTempBytes(bytes: ByteArray, ext: String, block: (String) -> T): T {
        val tmp = storageRoot / "inner-tmp-${Uuid.random()}.$ext"
        FileSystem.SYSTEM.write(tmp) { write(bytes) }
        return try {
            block(tmp.toString())
        } finally {
            runCatching { FileSystem.SYSTEM.delete(tmp, mustExist = false) }
        }
    }
}
