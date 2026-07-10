package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.data.local.work.CoverStore
import com.neoutils.opentoons.util.CoverEncoder
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Capa de obra derivada (task 6.3 / D5): a `cover.webp` é gerada **uma vez, no import**, a
 * partir da página de capa — thumbnail derivada, reduzida — e as páginas dos `.opz` **não** são
 * tocadas (seguem STORED cru, nenhuma transcodificada).
 */
class CoverStoreJvmTest {

    private val fs = FileSystem.SYSTEM

    private fun tempObraDir(): Path {
        val dir = File(File.createTempFile("opentoons-cover", "").apply { delete() }, "obra").apply { mkdirs() }
        dir.deleteOnExit()
        return dir.absolutePath.toPath()
    }

    /** PNG real (o encoder de capa da JVM usa ImageIO, que precisa de bytes decodificáveis). */
    private fun pngBytes(w: Int, h: Int): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until w) for (y in 0 until h) img.setRGB(x, y, (x * 7 + y * 13) and 0xFFFFFF)
        return ByteArrayOutputStream().use { ImageIO.write(img, "png", it); it.toByteArray() }
    }

    private fun writeChapter(obraDir: Path, name: String, chapterId: String, page: String): Path {
        val opz = obraDir / "$name.opz"
        OpzWriter.write(fs, opz, chapterId = chapterId, obraId = "obra-1") { sink ->
            sink.page(page, pngBytes(1200, 1600))
        }
        return opz
    }

    @Test
    fun geraCover_apartirDaPaginaDeCapa_decodificavel_paginasIntactas() {
        val obraDir = tempObraDir()
        val opz = writeChapter(obraDir, "Cap 1", "cap-1", "001.png")

        val coverPath = CoverStore.generate(fs, obraDir, opz, "001.png")
        assertNotNull(coverPath)
        assertTrue(fs.exists(coverPath))
        // A thumbnail é uma imagem válida e menor que a página original.
        val thumb = ImageIO.read(File(coverPath.toString()))
        assertNotNull(thumb)
        assertTrue(maxOf(thumb.width, thumb.height) <= 512)
        // Páginas intactas (STORED): gerar a capa não toca no `.opz` do capítulo.
        assertEquals(listOf("001.png"), OpzReader.pageNames(opz.toString()))
    }

    @Test
    fun writeFromBytes_geraCoverDeImagemExterna_decodificavelEReduzida() {
        val obraDir = tempObraDir()

        // Capa autônoma (improve-import): bytes de imagem externa, sem página/OPZ de origem.
        val coverPath = CoverStore.writeFromBytes(fs, obraDir, pngBytes(1200, 1600))
        assertNotNull(coverPath)
        assertTrue(fs.exists(coverPath))
        val thumb = ImageIO.read(File(coverPath.toString()))
        assertNotNull(thumb)
        assertTrue(maxOf(thumb.width, thumb.height) <= 512)
    }

    @Test
    fun writeFromBytes_imagemIlegivel_naoGravaCapa() {
        val obraDir = tempObraDir()

        // Nada é gravado: quem escolheu a imagem precisa ser avisado, não ficar sem capa em silêncio.
        assertNull(CoverStore.writeFromBytes(fs, obraDir, "isto não é uma imagem".encodeToByteArray()))
        assertFalse(fs.exists(CoverStore.pathIn(obraDir)))
    }

    @Test
    fun writeEncoded_gravaOsBytesIntactos_semReencodar() {
        val obraDir = tempObraDir()

        // A capa externa chega já reduzida pelo CoverEncoder na revisão: gravar de novo pelo
        // encoder só degradaria a imagem. Os bytes em disco são exatamente os recebidos.
        val thumbnail = CoverEncoder.encodeThumbnail(pngBytes(1200, 1600))
        assertNotNull(thumbnail)
        val coverPath = CoverStore.writeEncoded(fs, obraDir, thumbnail)
        assertNotNull(coverPath)
        assertContentEquals(thumbnail, fs.read(coverPath) { readByteArray() })
    }

    @Test
    fun writeEncoded_imagemIlegivel_naoGravaCapa() {
        val obraDir = tempObraDir()

        // Grava direto, mas não às cegas: bytes que não decodificam não viram capa.
        assertNull(CoverStore.writeEncoded(fs, obraDir, "isto não é uma imagem".encodeToByteArray()))
        assertFalse(fs.exists(CoverStore.pathIn(obraDir)))
    }

    @Test
    fun imageIO_temReaderWebp_noDesktop() {
        // Bug improve-import: escolher uma capa `.webp` "não fazia nada" no desktop porque o
        // ImageIO padrão não tem reader de webp. O plugin TwelveMonkeys registra um via
        // ServiceLoader — sem ele, `CoverEncoder.encodeThumbnail(webp)` devolveria null.
        assertTrue(ImageIO.getImageReadersBySuffix("webp").hasNext(), "sem reader webp no ImageIO")
        assertTrue(ImageIO.getImageReadersByFormatName("webp").hasNext(), "sem reader webp por formato")
    }
}
