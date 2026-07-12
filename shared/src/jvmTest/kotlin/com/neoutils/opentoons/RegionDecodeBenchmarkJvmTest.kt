package com.neoutils.opentoons

import com.neoutils.opentoons.data.image.decodeRegion
import com.neoutils.opentoons.ui.reader.LongStripLayout
import com.neoutils.opentoons.ui.reader.PageGeometry
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Benchmark do custo de decode por região no path JVM (tasks 5.11/5.10). Mede se o decode de
 * uma faixa custa proporcional ao `srcTop` (re-varredura desde o topo, já que JPEG/PNG não têm
 * seek de linha) e o total de percorrer a página tile a tile para vários `tileHeightPx` — o
 * dado que fixa o `tileHeightPx` (Open Question do design).
 *
 * Não é um teste de asserção: mede e escreve um relatório. Roda sob demanda; a saída fica em
 * `$tmp/opentoons-region-bench.txt`.
 */
class RegionDecodeBenchmarkJvmTest {

    private val width = 1080
    private val height = 6000
    private val report = StringBuilder()

    @Test
    fun benchmarkRegionDecode() {
        val jpeg = encode("jpg")
        val png = encode("png")

        report.appendLine("# Region decode benchmark (JVM/ImageIO)")
        report.appendLine("página sintética: ${width}x$height  |  jpeg=${jpeg.size / 1024}KB  png=${png.size / 1024}KB")
        report.appendLine()

        for ((label, bytes) in listOf("JPEG" to jpeg, "PNG" to png)) {
            report.appendLine("## $label")
            // Baseline: um único decode da página inteira (a alternativa rejeitada no design).
            val fullMs = timeMs { ImageIO.read(bytes.inputStream()) }
            report.appendLine("decode inteiro (baseline): ${fullMs}ms")

            for (tileHeight in listOf(1024, 2048, 3072, 4096)) {
                val layout = LongStripLayout(listOf(PageGeometry(width, height)), width, tileHeight)
                val tiles = layout.tiles
                // Aquecimento (JIT + tabelas do reader).
                decodeRegion(bytes, 0, tiles.first().srcHeight, width)

                val perTile = tiles.map { t ->
                    timeMs { decodeRegion(bytes, t.srcTop, t.srcHeight, width) }
                }
                val total = perTile.sum()
                val first = perTile.first()
                val last = perTile.last()
                report.appendLine(
                    "tileHeight=$tileHeight  tiles=${tiles.size}  " +
                        "total=${total}ms  1º=${first}ms  último=${last}ms  " +
                        "último/1º=${ratio(last, first)}  total/baseline=${ratio(total, fullMs)}",
                )
            }
            report.appendLine()
        }

        val out = File(System.getProperty("java.io.tmpdir"), "opentoons-region-bench.txt")
        out.writeText(report.toString())
        println(report.toString())
    }

    // Imagem com ruído determinístico por pixel — impede o JPEG/PNG de comprimir a quase nada
    // (o que tornaria o decode trivial e a medição irrealista).
    private fun encode(format: String): ByteArray {
        val type = if (format == "jpg") BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val img = BufferedImage(width, height, type)
        var seed = 0x9E3779B9.toInt()
        for (y in 0 until height) {
            for (x in 0 until width) {
                seed = seed * 1664525 + 1013904223
                val r = (seed ushr 16) and 0xFF
                val g = (x xor y) and 0xFF
                val b = (y ushr 3) and 0xFF
                img.setRGB(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        val baos = ByteArrayOutputStream()
        ImageIO.write(img, format, baos)
        return baos.toByteArray()
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun ratio(a: Long, b: Long): String =
        if (b == 0L) "∞" else ((a.toDouble() / b) * 10).toLong().let { "${it / 10}.${it % 10}x" }
}
