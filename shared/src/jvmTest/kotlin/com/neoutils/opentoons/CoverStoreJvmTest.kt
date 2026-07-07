package com.neoutils.opentoons

import com.neoutils.opentoons.data.local.opz.OpzReader
import com.neoutils.opentoons.data.local.opz.OpzWriter
import com.neoutils.opentoons.data.local.work.CoverStore
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
