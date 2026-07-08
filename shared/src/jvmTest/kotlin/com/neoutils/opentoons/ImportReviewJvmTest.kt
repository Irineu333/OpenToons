package com.neoutils.opentoons

import androidx.room.Room
import com.neoutils.opentoons.data.db.OpenToonsDatabase
import com.neoutils.opentoons.data.db.buildDatabase
import com.neoutils.opentoons.data.importer.ContentImporter
import com.neoutils.opentoons.data.importer.CoverChoice
import com.neoutils.opentoons.data.importer.ImportEdits
import com.neoutils.opentoons.data.local.work.CoverSource
import com.neoutils.opentoons.data.local.work.CoverStore
import com.neoutils.opentoons.data.local.work.WorkManifestStore
import com.neoutils.opentoons.data.repository.LibraryRepository
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Import em duas fases (edit-import-metadata): [ContentImporter.prepare] prepara a revisão **sem
 * gravar nada**, [ContentImporter.cancel] descarta a origem sem deixar rastro, e
 * [ContentImporter.commit] materializa com os valores editados — título/descrição no `work.json`
 * e a capa vinda da **página escolhida** ({chapterId, entryName}). Usa a `filesDir` real do
 * FileKit (storage do app), limpando os artefatos criados por cada teste.
 */
@OptIn(ExperimentalUuidApi::class)
class ImportReviewJvmTest {

    private val fs = FileSystem.SYSTEM
    private lateinit var root: File
    private lateinit var obrasDir: File
    private val createdWorkUuids = mutableListOf<String>()
    private val createdSources = mutableListOf<File>()

    @BeforeTest
    fun setUp() {
        runCatching { FileKit.init(appId = "OpenToonsImportTest") }
        root = File(FileKit.filesDir.path)
        obrasDir = File(root, "obras")
    }

    @AfterTest
    fun tearDown() {
        createdWorkUuids.forEach { uuid ->
            runCatching { fs.deleteRecursively((obrasDir.absolutePath.toPath() / uuid), mustExist = false) }
        }
        createdSources.forEach { runCatching { it.delete() } }
    }

    private fun newImporter(): ContentImporter {
        val dbFile = File.createTempFile("opentoons-import", ".db").apply { deleteOnExit() }
        val db: OpenToonsDatabase = buildDatabase(Room.databaseBuilder<OpenToonsDatabase>(name = dbFile.absolutePath))
        return ContentImporter(LibraryRepository(db.workDao(), db.chapterDao(), db.progressDao()))
    }

    /** PNG real (thumbnails/capa usam ImageIO, que precisa de bytes decodificáveis). */
    private fun pngBytes(seed: Int): ByteArray {
        val img = BufferedImage(300, 400, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 300) for (y in 0 until 400) img.setRGB(x, y, (x * seed + y * 13) and 0xFFFFFF)
        return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }
    }

    /** Cria um `.cbz` (unidade) com duas pastas-capítulo em `filesDir` e o devolve como PlatformFile. */
    private fun sourceCbz(): PlatformFile {
        val name = "test-src-${Uuid.random()}.cbz"
        val file = File(root, name).apply { parentFile?.mkdirs() }
        ZipOutputStream(file.outputStream()).use { zip ->
            mapOf(
                "Cap 1/001.png" to pngBytes(7),
                "Cap 1/002.png" to pngBytes(11),
                "Cap 2/001.png" to pngBytes(23),
            ).forEach { (entry, bytes) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        createdSources += file
        return PlatformFile(FileKit.filesDir, name)
    }

    @Test
    fun prepare_naoGravaNada_eCancelDescartaOrigem() = runBlocking {
        val importer = newImporter()
        val obrasBefore = obrasDir.list()?.size ?: 0

        val draft = importer.prepare(sourceCbz())

        // Duas pastas → dois capítulos → duas candidatas a capa; default = 1ª página do 1º.
        assertEquals(2, draft.coverCandidates.size)
        assertNotNull(draft.defaultCover)
        // A origem foi retida (temp existe); nenhuma obra foi materializada.
        val tempFile = File(draft.sourceTempPath)
        assertTrue(tempFile.exists())
        assertEquals(obrasBefore, obrasDir.list()?.size ?: 0)

        importer.cancel(draft)

        // Cancelar não deixa rastro: temp apagado, nenhuma obra criada.
        assertFalse(tempFile.exists())
        assertEquals(obrasBefore, obrasDir.list()?.size ?: 0)
    }

    @Test
    fun commit_aplicaEdicoes_eCapaVemDaPaginaEscolhida() = runBlocking {
        val importer = newImporter()
        val draft = importer.prepare(sourceCbz())

        // Escolhe a capa na 2ª candidata (1ª página do 2º capítulo) — não o default.
        val chosen = draft.coverCandidates[1]
        val work = importer.commit(
            draft,
            ImportEdits(
                title = "Título Editado",
                description = "Sinopse editada",
                cover = CoverChoice.Page(chosen.chapterId, chosen.entryName),
            ),
        )
        createdWorkUuids += work.id.uuid

        // Valores editados no domínio.
        assertEquals("Título Editado", work.title)
        assertEquals("Sinopse editada", work.description)

        // work.json (fonte de verdade) reflete título/descrição e a proveniência da capa (página).
        val obraDir = obrasDir.absolutePath.toPath() / work.id.uuid
        val manifest = WorkManifestStore.read(fs, obraDir)
        assertNotNull(manifest)
        assertEquals("Título Editado", manifest.title)
        assertEquals("Sinopse editada", manifest.description)
        assertEquals(CoverSource.PAGE, manifest.cover?.source)
        assertEquals(chosen.chapterId, manifest.cover?.chapterId)
        assertEquals(chosen.entryName, manifest.cover?.entryName)

        // cover.webp derivada foi gerada; a origem temporária foi consumida.
        assertTrue(fs.exists(CoverStore.pathIn(obraDir)))
        assertFalse(File(draft.sourceTempPath).exists())
    }

    @Test
    fun commit_comImagemExterna_gravaCapaAutonoma_semReferenciaDePagina() = runBlocking {
        val importer = newImporter()
        val draft = importer.prepare(sourceCbz())

        // Capa externa: bytes de uma imagem que não é página nenhuma da obra.
        val external = pngBytes(99)
        val work = importer.commit(
            draft,
            ImportEdits(
                title = "Com Capa Externa",
                description = "",
                cover = CoverChoice.External(external),
            ),
        )
        createdWorkUuids += work.id.uuid

        val obraDir = obrasDir.absolutePath.toPath() / work.id.uuid
        val manifest = WorkManifestStore.read(fs, obraDir)
        assertNotNull(manifest)
        // Proveniência = externa, sem {chapterId, entryName}; a capa é autônoma.
        assertEquals(CoverSource.EXTERNAL, manifest.cover?.source)
        assertNull(manifest.cover?.chapterId)
        assertNull(manifest.cover?.entryName)
        // cover.webp foi gerada a partir dos bytes externos.
        assertTrue(fs.exists(CoverStore.pathIn(obraDir)))
    }
}
