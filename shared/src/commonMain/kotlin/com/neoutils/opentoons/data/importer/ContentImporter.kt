package com.neoutils.opentoons.data.importer

import com.neoutils.opentoons.data.db.ChapterEntity
import com.neoutils.opentoons.data.db.WorkEntity
import com.neoutils.opentoons.data.db.toDomain
import com.neoutils.opentoons.data.local.ArchiveReader
import com.neoutils.opentoons.data.local.ContainerFormats
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.data.local.rar.UnsupportedFormatException
import com.neoutils.opentoons.data.local.work.CoverStore
import com.neoutils.opentoons.data.local.work.WorkCover
import com.neoutils.opentoons.data.local.work.WorkManifest
import com.neoutils.opentoons.data.local.work.WorkManifestStore
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.util.CoverEncoder
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
 * Import unificado (specs content-import; edit-import-metadata). **Normaliza qualquer formato de
 * origem para OPZ por capítulo** (D1): decodifica CBZ/CBR/ZIP/RAR e materializa um `.opz`
 * STORED por capítulo em `obras/{obra}/{capítulo}.opz` (D2).
 *
 * O import de **nova obra** é em **duas fases** (edit-import-metadata D1): [prepare] abre a
 * origem, planeja os capítulos **em memória** (gera um `chapterId` estável por capítulo e
 * decodifica a thumbnail da 1ª página de cada um) e retém a origem num temp — **sem gravar**
 * OPZ/`work.json`/`cover.webp`/banco. O usuário revisa título/descrição/capa e então [commit]
 * materializa tudo com os valores editados, ou [cancel] descarta a origem sem deixar rastro.
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

    // --- Fase A: preparar a revisão (nada gravado) ---

    /**
     * **Fase A** do import de uma nova obra (edit-import-metadata D1): copia a origem para um
     * temp **retido**, planeja os capítulos em memória (gerando `chapterId` estável) e decodifica
     * a thumbnail da 1ª página de cada capítulo como candidata a capa. **Não grava** nenhum
     * `.opz`/`work.json`/`cover.webp` nem toca o banco. A origem retida é consumida por [commit]
     * ou descartada por [cancel]; se esta função falhar, o temp é limpo aqui mesmo.
     */
    suspend fun prepare(picked: PlatformFile): ImportDraft = withContext(ioDispatcher) {
        val sourceTemp = retainTempSource(picked)
        val defaultTitle = picked.name.substringBeforeLast('.').ifBlank { "Sem título" }
        try {
            val previews = ContainerFormats.open(sourceTemp.toString()).use { reader ->
                forEachChapter(reader, allowPackage = true, baseOrder = 0, onProgress = {}) { _, title, _, pages ->
                    val first = pages.first()
                    ChapterPreview(title = title, entryName = first.opzName, firstPageBytes = first.read())
                }
            }
            require(previews.isNotEmpty()) { "Nenhuma imagem encontrada no arquivo." }
            // 1 candidata por capítulo = a 1ª página (v1). O `chapterId` gerado aqui é o mesmo
            // usado na materialização (D3) — a referência {chapterId, entryName} escolhida na
            // revisão permanece válida quando o OPZ é gravado no commit.
            val candidates = previews.map { p ->
                CoverCandidate(
                    chapterId = Uuid.random().toString(),
                    chapterTitle = p.title,
                    entryName = p.entryName,
                    thumbnail = CoverEncoder.encodeThumbnail(p.firstPageBytes),
                )
            }
            ImportDraft(
                sourceTempPath = sourceTemp.toString(),
                defaultTitle = defaultTitle,
                chapterIds = candidates.map { it.chapterId },
                coverCandidates = candidates,
                defaultCover = candidates.firstOrNull()?.let { CoverChoice.Page(it.chapterId, it.entryName) },
            )
        } catch (e: Throwable) {
            runCatching { FileSystem.SYSTEM.delete(sourceTemp, mustExist = false) }
            throw e
        }
    }

    /** Descarta uma revisão sem materializar (edit-import-metadata): apaga a origem retida. */
    fun cancel(draft: ImportDraft) {
        runCatching { FileSystem.SYSTEM.delete(draft.sourceTempPath.toPath(), mustExist = false) }
    }

    // --- Fase B: materializar com os valores editados ---

    /**
     * **Fase B** do import (edit-import-metadata D1): materializa OPZ por capítulo a partir da
     * origem retida em [draft] (reatribuindo os `chapterId` planejados na Fase A), grava o
     * `work.json` com `title`/`description`/`cover` de [edits], gera a `cover.webp` da página
     * escolhida e indexa no banco. A origem temporária é sempre consumida (apagada) ao final.
     */
    suspend fun commit(
        draft: ImportDraft,
        edits: ImportEdits,
        onProgress: (String) -> Unit = {},
    ): Work = withContext(ioDispatcher) {
        val workUuid = Uuid.random().toString()
        val obraDir = storageRoot / "obras" / workUuid
        FileSystem.SYSTEM.createDirectories(obraDir)

        val results = try {
            onProgress("Lendo arquivo…")
            ContainerFormats.open(draft.sourceTempPath).use { reader ->
                forEachChapter(reader, allowPackage = true, baseOrder = 0, onProgress) { ordinal, title, orderIndex, pages ->
                    // Reusa o `chapterId` planejado na Fase A (mesma ordem/sequência); só cai no
                    // aleatório se o plano divergir (não deveria — a origem é o temp imutável).
                    val chapterId = draft.chapterIds.getOrNull(ordinal) ?: Uuid.random().toString()
                    writeChapterOpz(obraDir, workUuid, chapterId, title, orderIndex, pages)
                }
            }
        } catch (e: Throwable) {
            runCatching { FileSystem.SYSTEM.deleteRecursively(obraDir, mustExist = false) }
            throw e
        } finally {
            runCatching { FileSystem.SYSTEM.delete(draft.sourceTempPath.toPath(), mustExist = false) }
        }
        if (results.isEmpty()) {
            runCatching { FileSystem.SYSTEM.deleteRecursively(obraDir, mustExist = false) }
            throw IllegalArgumentException("Nenhuma imagem encontrada no arquivo.")
        }

        // Direção **detectada** (dado). Heurística de direção fica para o futuro — LTR default.
        val direction = ReadingDirection.LTR
        val title = edits.title.trim().ifBlank { draft.defaultTitle }.ifBlank { "Sem título" }
        val description = edits.description.trim()
        // Capa autônoma (improve-import): materializa a `cover.webp` a partir da fonte escolhida —
        // página da obra (default) ou imagem externa — e registra a proveniência no `work.json`.
        onProgress("Gerando capa…")
        val (cover, coverPath) = try {
            materializeCover(edits.cover, obraDir, results)
        } catch (e: Throwable) {
            runCatching { FileSystem.SYSTEM.deleteRecursively(obraDir, mustExist = false) }
            throw e
        }

        // `work.json` **antes** do banco (D6): evita órfãos e é a fonte de verdade do dado.
        onProgress("Gravando manifesto…")
        WorkManifestStore.write(
            FileSystem.SYSTEM,
            obraDir,
            WorkManifest(
                obraId = workUuid,
                title = title,
                description = description,
                direction = direction.name,
                cover = cover,
            ),
        )

        val work = WorkEntity(
            uuid = workUuid,
            publisherKey = null, // sem publicador atribuível (ADR-0009)
            title = title,
            description = description,
            coverPath = coverPath,
            direction = direction.name,
            directionOverride = null,
            layoutOverride = null,
            favorite = false,
            createdAt = nowMillis(),
        )
        repository.addWork(work, results.map { it.first })
        work.toDomain()
    }

    /**
     * Materializa a capa da obra e devolve o par (proveniência p/ `work.json`, caminho da
     * `cover.webp`). A `cover.webp` é gerada a partir dos **bytes da fonte escolhida**: uma imagem
     * externa (grava direto, já reduzida na revisão), ou a página escolhida (extrai do OPZ e
     * reduz). Se a escolha for inválida ou ausente, cai na **1ª página materializada** (default).
     * Nenhuma página é transcodificada.
     *
     * Uma imagem externa ilegível **falha o import** em vez de materializar a obra sem a capa que o
     * usuário escolheu; a capa de página cai no default porque a obra existe mesmo sem thumbnail.
     */
    private fun materializeCover(
        choice: CoverChoice?,
        obraDir: Path,
        results: List<Pair<ChapterEntity, String?>>,
    ): Pair<WorkCover?, String?> {
        val fs = FileSystem.SYSTEM
        when (choice) {
            is CoverChoice.External -> {
                val path = CoverStore.writeEncoded(fs, obraDir, choice.bytes)?.toString()
                    ?: throw IllegalArgumentException("Não foi possível ler a imagem escolhida como capa.")
                return WorkCover.external() to path
            }
            is CoverChoice.Page -> {
                val opz = results.firstOrNull { it.first.id == choice.chapterId }?.first?.archivePath
                if (opz != null) {
                    val path = CoverStore.generate(fs, obraDir, opz.toPath(), choice.entryName)?.toString()
                    return WorkCover.page(choice.chapterId, choice.entryName) to path
                }
            }
            null -> Unit
        }
        // Fallback: 1ª página materializada do 1º capítulo (default da spec).
        val fallback = results.firstOrNull { it.second != null } ?: return null to null
        val path = CoverStore.generate(fs, obraDir, fallback.first.archivePath.toPath(), fallback.second!!)?.toString()
        return WorkCover.page(fallback.first.id, fallback.second!!) to path
    }

    /**
     * Limpa temps órfãos de import (edit-import-metadata task 2.6): drafts abandonados (app
     * morto no meio da revisão) e temporários de extração. Seguro chamar na inicialização, sem
     * import ativo.
     */
    fun cleanupOrphanTemps() {
        val fs = FileSystem.SYSTEM
        if (fs.metadataOrNull(storageRoot)?.isDirectory != true) return
        fs.list(storageRoot).forEach { path ->
            val n = path.name
            if (n.startsWith(DRAFT_PREFIX) || n.startsWith("import-tmp-") || n.startsWith("inner-tmp-")) {
                runCatching { fs.delete(path, mustExist = false) }
            }
        }
    }

    /**
     * Adiciona capítulos a uma **obra existente** a partir de arquivos-**unidade** (CBZ/CBR).
     * Pacotes (ZIP/RAR) são recusados. Retorna quantos capítulos entraram. Não passa por revisão
     * (metadados da obra já existem; a capa é a gerada no import — D5).
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
                forEachChapter(reader, allowPackage = false, base, onProgress) { _, title, orderIndex, pages ->
                    writeChapterOpz(obraDir, workUuid, Uuid.random().toString(), title, orderIndex, pages)
                }
            }
        }
        require(results.isNotEmpty()) { "Nenhuma imagem encontrada no arquivo." }
        results.forEach { repository.addChapter(it.first) }
        results.size
    }

    // --- Travessia dos capítulos (streaming; compartilhada por preview e materialização) ---

    /**
     * Percorre os capítulos da origem em ordem natural, chamando [onChapter] por capítulo
     * (`cbz`/`cbr` = unidade por pasta; `zip`/`rar` = pacote por arquivo interno). Cada chamada
     * recebe o **ordinal** (0-based, entre capítulos não vazios — estável entre preview e
     * materialização), título, `orderIndex` e as páginas planejadas (leitura sob demanda). Só as
     * páginas são lidas do container; nada é gravado por esta função em si.
     */
    private fun <T> forEachChapter(
        reader: ArchiveReader,
        allowPackage: Boolean,
        baseOrder: Int,
        onProgress: (String) -> Unit,
        onChapter: (ordinal: Int, title: String, orderIndex: Int, pages: List<PlannedPage>) -> T?,
    ): List<T> {
        val entries = reader.entryNames()
        return if (ArchiveReader.isPackage(entries)) {
            if (!allowPackage) {
                throw UnsupportedFormatException(
                    "Pacotes (ZIP/RAR) não podem ser adicionados dentro de uma obra. " +
                        "Importe um arquivo CBZ/CBR (unidade).",
                )
            }
            packageChapters(reader, entries, baseOrder, onProgress, onChapter)
        } else {
            unitChapters(reader, entries, baseOrder, onProgress, onChapter)
        }
    }

    /** Unidade: imagens agrupadas por pasta — cada pasta um capítulo (raiz = um capítulo só). */
    private fun <T> unitChapters(
        reader: ArchiveReader,
        entries: List<String>,
        baseOrder: Int,
        onProgress: (String) -> Unit,
        onChapter: (Int, String, Int, List<PlannedPage>) -> T?,
    ): List<T> {
        val images = entries.filter { ArchiveReader.isImage(it) }.sortedWith(NaturalOrder)
        if (images.isEmpty()) return emptyList()
        val grouped = images
            .groupBy { it.substringBeforeLast('/', "") }
            .map { (dir, es) -> dir to es.sortedWith(NaturalOrder) }
            .sortedWith { a, b -> NaturalOrder.compare(a.first, b.first) }

        val out = ArrayList<T>(grouped.size)
        grouped.forEachIndexed { ordinal, (dir, names) ->
            onProgress("Convertendo capítulo ${ordinal + 1}/${grouped.size}…")
            val pages = names.map { name -> PlannedPage(basename(name)) { reader.read(name) } }
            onChapter(ordinal, chapterTitle(dir, ordinal, grouped.size), baseOrder + ordinal, pages)?.let { out += it }
        }
        return out
    }

    /** Pacote: cada arquivo interno (`.cbz`/`.cbr`) vira um capítulo (imagens achatadas). */
    private fun <T> packageChapters(
        reader: ArchiveReader,
        entries: List<String>,
        baseOrder: Int,
        onProgress: (String) -> Unit,
        onChapter: (Int, String, Int, List<PlannedPage>) -> T?,
    ): List<T> {
        val inner = entries.filter { ArchiveReader.isArchive(it) }.sortedWith(NaturalOrder)
        val out = ArrayList<T>(inner.size)
        var ordinal = 0
        inner.forEach { innerName ->
            val bytes = reader.read(innerName)
            val ext = innerName.substringAfterLast('.', "cbz")
            withTempBytes(bytes, ext) { innerPath ->
                ContainerFormats.open(innerPath).use { innerReader ->
                    val images = innerReader.entryNames()
                        .filter { ArchiveReader.isImage(it) }
                        .sortedWith(NaturalOrder)
                    if (images.isNotEmpty()) {
                        onProgress("Convertendo capítulo ${ordinal + 1}/${inner.size}…")
                        val pages = images.map { name -> PlannedPage(basename(name)) { innerReader.read(name) } }
                        val title = basename(innerName).substringBeforeLast('.')
                        onChapter(ordinal, title, baseOrder + ordinal, pages)?.let { out += it }
                        ordinal++
                    }
                }
            }
        }
        return out
    }

    /** Escreve um capítulo como OPZ (streaming) e devolve a entidade + a 1ª página (capa). */
    private fun writeChapterOpz(
        obraDir: Path,
        workUuid: String,
        chapterId: String,
        title: String,
        orderIndex: Int,
        pages: List<PlannedPage>,
    ): Pair<ChapterEntity, String?>? {
        if (pages.isEmpty()) return null
        // Nome do `.opz` = **título** do capítulo (sanitizado); a ordem sai do natural sort dos
        // nomes (D3). O `chapterId` interno é a chave estável de estado (sobrevive a rename) e
        // é atribuído pelo chamador (planejado na Fase A do import de nova obra).
        val opzPath = obraDir / uniqueOpzName(obraDir, title)
        val result = OpzWriter.write(FileSystem.SYSTEM, opzPath, chapterId = chapterId, obraId = workUuid) { sink ->
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

    /**
     * Nome de arquivo único para o `.opz` do capítulo: título sanitizado + `.opz`, com sufixo
     * ` (n)` se já existir na pasta (dois capítulos de mesmo título, ou adição sobre existente).
     * O título continua vivo no nome; o `chapterId` interno é quem identifica o capítulo.
     */
    private fun uniqueOpzName(obraDir: Path, title: String): String {
        val base = sanitizeFileName(title).ifBlank { "Capítulo" }
        var candidate = "$base.opz"
        var n = 2
        while (FileSystem.SYSTEM.exists(obraDir / candidate)) {
            candidate = "$base ($n).opz"
            n++
        }
        return candidate
    }

    /** Neutraliza caracteres inválidos em nome de arquivo (cross-plataforma) e trim de bordas. */
    private fun sanitizeFileName(name: String): String =
        name.map { c -> if (c in "/\\:*?\"<>|" || c.isISOControl()) '_' else c }
            .joinToString("")
            .trim()
            .trim('.')

    // --- Arquivos temporários (o picker pode ser uma URI; RAR/ZIP precisam de um path real) ---

    /** Prévia de capítulo usada na Fase A: título, nome da 1ª página no OPZ e seus bytes crus. */
    private class ChapterPreview(val title: String, val entryName: String, val firstPageBytes: ByteArray)

    /** Copia a origem para um temp **retido** (não apaga ao sair): consumido por commit/cancel. */
    private suspend fun retainTempSource(picked: PlatformFile): Path {
        val ext = picked.name.substringAfterLast('.', "").ifBlank { "zip" }
        val tmp = PlatformFile(FileKit.filesDir, "$DRAFT_PREFIX${Uuid.random()}.$ext")
        picked.copyTo(tmp)
        return tmp.path.toPath()
    }

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

    private companion object {
        /** Prefixo do temp retido de um draft de import (limpo por [cleanupOrphanTemps]). */
        const val DRAFT_PREFIX = "import-draft-"
    }
}

/**
 * Rascunho de import de uma **nova obra** (edit-import-metadata Fase A): tudo o que a revisão
 * precisa, sem nada gravado. [sourceTempPath] é a origem retida (consumida por commit/cancel);
 * [chapterIds] são os ids estáveis planejados (reatribuídos na materialização); [coverCandidates]
 * são as páginas oferecidas como capa; [defaultCover]/[defaultTitle] são os valores propostos.
 */
data class ImportDraft(
    val sourceTempPath: String,
    val defaultTitle: String,
    val chapterIds: List<String>,
    val coverCandidates: List<CoverCandidate>,
    val defaultCover: CoverChoice?,
)

/**
 * Fonte da capa escolhida na revisão (improve-import): uma **página** da própria obra (default,
 * `{chapterId, entryName}`) ou uma **imagem externa** (bytes já retidos em memória durante a
 * revisão, gravados como `cover.webp` no commit). Distintas apenas pela origem — a capa resultante
 * é sempre uma imagem autônoma da obra.
 */
sealed interface CoverChoice {
    data class Page(val chapterId: String, val entryName: String) : CoverChoice

    /** [bytes] são a thumbnail **já reduzida** pelo `CoverEncoder` na revisão — gravada como está. */
    class External(val bytes: ByteArray) : CoverChoice
}

/** Candidata a capa na revisão: 1ª página de um capítulo + sua thumbnail (bytes já codificados). */
data class CoverCandidate(
    val chapterId: String,
    val chapterTitle: String,
    val entryName: String,
    val thumbnail: ByteArray?,
)

/** Valores editados pelo usuário na revisão, aplicados por [ContentImporter.commit]. */
data class ImportEdits(
    val title: String,
    val description: String,
    val cover: CoverChoice?,
)
