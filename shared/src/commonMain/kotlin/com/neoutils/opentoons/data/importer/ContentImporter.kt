package com.neoutils.opentoons.data.importer

import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.data.local.ArchiveReader
import com.neoutils.opentoons.data.local.ContainerFormats
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.data.local.rar.UnsupportedFormatException
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.util.NaturalOrder
import com.neoutils.opentoons.util.ioDispatcher
import com.neoutils.opentoons.util.nowMillis
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
 * origem para OPZ por capítulo** (D1): decodifica CBZ/CBR/ZIP/RAR e materializa um `.opz`
 * STORED por capítulo em `obras/{obra}/{capítulo}.opz` (D2).
 *
 * Grade 2×2 (D3): `cbz`/`cbr` são **unidade** (imagens → capítulos por pasta); `zip`/`rar`
 * são **pacote** (arquivos `.cbz`/`.cbr` internos, cada um um capítulo). Desambiguação por
 * presença de imagem (ver [ArchiveReader.isPackage]).
 *
 * **Memória:** a escrita OPZ é streaming (pico = 1 página) e as páginas são lidas do container
 * de origem **sob demanda** — nunca se mantém um capítulo (menos ainda o volume) inteiro em
 * memória. RAR vive só aqui (leitura por entrada, D5); a leitura em regime é Okio-pura sobre OPZ.
 */
@OptIn(ExperimentalUuidApi::class)
class ContentImporter(
    private val repository: LibraryRepository,
) {

    /** Página planejada: nome plano no OPZ + leitura sob demanda dos bytes de origem. */
    private class PlannedPage(val opzName: String, val read: () -> ByteArray)

    private val storageRoot: Path get() = FileKit.filesDir.path.toPath()

    /** Importa uma **nova obra** a partir de CBZ/CBR/ZIP/RAR (pacotes permitidos). */
    suspend fun importWork(
        picked: PlatformFile,
        onProgress: (String) -> Unit = {},
    ): Work = withContext(ioDispatcher) {
        val workUuid = Uuid.random().toString()
        val title = picked.name.substringBeforeLast('.').ifBlank { "Sem título" }
        val obraDir = storageRoot / "obras" / workUuid
        FileSystem.SYSTEM.createDirectories(obraDir)

        onProgress("Lendo arquivo…")
        val results = withTempSource(picked) { sourcePath ->
            ContainerFormats.open(sourcePath).use { reader ->
                buildChapters(reader, allowPackage = true, workUuid, obraDir, baseOrder = 0, onProgress)
            }
        }
        require(results.isNotEmpty()) { "Nenhuma imagem encontrada no arquivo." }

        val cover = results.firstOrNull { it.second != null }
        val work = WorkEntity(
            uuid = workUuid,
            publisherKey = null, // sem publicador atribuível (ADR-0009)
            title = title,
            coverArchivePath = cover?.first?.archivePath,
            coverEntryName = cover?.second,
            direction = ReadingDirection.LTR.name,
            layoutOverride = null,
            favorite = false,
            createdAt = nowMillis(),
        )
        repository.addWork(work, results.map { it.first })
        work.toDomain()
    }

    /**
     * Adiciona capítulos a uma **obra existente** a partir de arquivos-**unidade** (CBZ/CBR).
     * Pacotes (ZIP/RAR) são recusados. Retorna quantos capítulos entraram.
     */
    suspend fun addChapters(
        workUuid: String,
        picked: PlatformFile,
        onProgress: (String) -> Unit = {},
    ): Int = withContext(ioDispatcher) {
        val obraDir = storageRoot / "obras" / workUuid
        FileSystem.SYSTEM.createDirectories(obraDir)
        val base = repository.nextOrderIndex(workUuid)

        onProgress("Lendo arquivo…")
        val results = withTempSource(picked) { sourcePath ->
            ContainerFormats.open(sourcePath).use { reader ->
                buildChapters(reader, allowPackage = false, workUuid, obraDir, base, onProgress)
            }
        }
        require(results.isNotEmpty()) { "Nenhuma imagem encontrada no arquivo." }
        results.forEach { repository.addChapter(it.first) }
        results.size
    }

    // --- Construção dos capítulos (streaming: origem → OPZ, capítulo a capítulo) ---

    private fun buildChapters(
        reader: ArchiveReader,
        allowPackage: Boolean,
        workUuid: String,
        obraDir: Path,
        baseOrder: Int,
        onProgress: (String) -> Unit,
    ): List<Pair<ChapterEntity, String?>> {
        val entries = reader.entryNames()
        return if (ArchiveReader.isPackage(entries)) {
            if (!allowPackage) {
                throw UnsupportedFormatException(
                    "Pacotes (ZIP/RAR) não podem ser adicionados dentro de uma obra. " +
                        "Importe um arquivo CBZ/CBR (unidade).",
                )
            }
            packageChapters(reader, entries, workUuid, obraDir, baseOrder, onProgress)
        } else {
            unitChapters(reader, entries, workUuid, obraDir, baseOrder, onProgress)
        }
    }

    /** Unidade: imagens agrupadas por pasta — cada pasta um capítulo (raiz = um capítulo só). */
    private fun unitChapters(
        reader: ArchiveReader,
        entries: List<String>,
        workUuid: String,
        obraDir: Path,
        baseOrder: Int,
        onProgress: (String) -> Unit,
    ): List<Pair<ChapterEntity, String?>> {
        val images = entries.filter { ArchiveReader.isImage(it) }.sortedWith(NaturalOrder)
        if (images.isEmpty()) return emptyList()
        val grouped = images
            .groupBy { it.substringBeforeLast('/', "") }
            .map { (dir, es) -> dir to es.sortedWith(NaturalOrder) }
            .sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }

        return grouped.mapIndexedNotNull { index, (dir, names) ->
            onProgress("Convertendo capítulo ${index + 1}/${grouped.size}…")
            val pages = names.map { name -> PlannedPage(basename(name)) { reader.read(name) } }
            writeChapterOpz(obraDir, workUuid, chapterTitle(dir, index, grouped.size), baseOrder + index, pages)
        }
    }

    /** Pacote: cada arquivo interno (`.cbz`/`.cbr`) vira um capítulo (imagens achatadas). */
    private fun packageChapters(
        reader: ArchiveReader,
        entries: List<String>,
        workUuid: String,
        obraDir: Path,
        baseOrder: Int,
        onProgress: (String) -> Unit,
    ): List<Pair<ChapterEntity, String?>> {
        val inner = entries.filter { ArchiveReader.isArchive(it) }.sortedWith(NaturalOrder)
        val out = ArrayList<Pair<ChapterEntity, String?>>(inner.size)
        inner.forEachIndexed { index, innerName ->
            onProgress("Convertendo capítulo ${index + 1}/${inner.size}…")
            val bytes = reader.read(innerName)
            val ext = innerName.substringAfterLast('.', "cbz")
            val written = withTempBytes(bytes, ext) { innerPath ->
                ContainerFormats.open(innerPath).use { innerReader ->
                    val images = innerReader.entryNames()
                        .filter { ArchiveReader.isImage(it) }
                        .sortedWith(NaturalOrder)
                    if (images.isEmpty()) return@use null
                    val pages = images.map { name -> PlannedPage(basename(name)) { innerReader.read(name) } }
                    val title = basename(innerName).substringBeforeLast('.')
                    writeChapterOpz(obraDir, workUuid, title, baseOrder + out.size, pages)
                }
            }
            written?.let { out += it }
        }
        return out
    }

    /** Escreve um capítulo como OPZ (streaming) e devolve a entidade + a 1ª página (capa). */
    private fun writeChapterOpz(
        obraDir: Path,
        workUuid: String,
        title: String,
        orderIndex: Int,
        pages: List<PlannedPage>,
    ): Pair<ChapterEntity, String?>? {
        if (pages.isEmpty()) return null
        val chapterId = Uuid.random().toString()
        val opzPath = obraDir / "$chapterId.opz"
        val result = OpzWriter.write(FileSystem.SYSTEM, opzPath, ReadingDirection.LTR) { sink ->
            pages.forEach { sink.page(it.opzName, it.read()) }
        }
        if (result.pageCount == 0) {
            runCatching { FileSystem.SYSTEM.delete(opzPath, mustExist = false) }
            return null
        }
        val entity = ChapterEntity(
            id = chapterId,
            workUuid = workUuid,
            title = title,
            archivePath = opzPath.toString(),
            orderIndex = orderIndex,
            pageCount = result.pageCount,
            sourceKey = LocalImportSource.KEY,
            detectedLayout = result.detectedLayout.name,
            layoutOverride = null,
        )
        return entity to result.firstPageName
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
