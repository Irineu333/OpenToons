package com.neoutils.opentoons.data.local.opz

import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.util.ImageSize
import com.neoutils.opentoons.util.LayoutHeuristic
import com.neoutils.opentoons.util.readImageSize
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.FileSystem
import okio.Path

/**
 * Escritor OPZ pura-Kotlin (design D6, task 2.2): grava um contêiner ZIP com entradas
 * **STORED** (sem compressão) — local header + CRC-32 + bytes crus, diretório central e EOCD —
 * mais o `manifest.json` (task 2.1). STORED dá leitura zero-copy na leitura em regime e
 * dispensa qualquer dependência nativa de *escrita*: o `openZip` do Okio reabre o resultado.
 *
 * **Streaming (pico de memória = 1 página):** as páginas são fornecidas via [block] e cada
 * uma é gravada na hora e descartada — nunca se mantém o capítulo inteiro em memória (o import
 * de volumes grandes, ex. ~284 MB, estourava o heap do Android). Só metadados por página (nome,
 * CRC, tamanho, offset, dims) ficam retidos, para o diretório central e o manifesto no fim.
 */
object OpzWriter {

    private val json = Json { encodeDefaults = true }

    private const val SIG_LOCAL = 0x04034b50
    private const val SIG_CENTRAL = 0x02014b50
    private const val SIG_EOCD = 0x06054b50
    private const val VERSION = 20
    private const val FLAG_UTF8 = 0x0800 // bit 11: nomes de entrada em UTF-8
    private const val METHOD_STORED = 0

    /** Resultado do write: contagem de páginas, layout detectado e a 1ª página (capa). */
    class Result(
        val pageCount: Int,
        val detectedLayout: Layout,
        val firstPageName: String?,
    )

    /** Entrada já resolvida (nome UTF-8, CRC, tamanho, offset do local header). */
    private class Written(val nameBytes: ByteArray, val crc: Long, val size: Int, val offset: Long)

    /**
     * Grava o OPZ em [outputPath]. As páginas são emitidas pelo [block] via [PageSink.page];
     * o `manifest.json` é montado no fim (ordem/dims coletadas + layout detectado + [direction]).
     * Retorna a contagem, o layout detectado (heurística sobre as dims amostradas) e a capa.
     */
    fun write(
        fileSystem: FileSystem,
        outputPath: Path,
        direction: ReadingDirection,
        sampleSize: Int = 8,
        block: (PageSink) -> Unit,
    ): Result {
        outputPath.parent?.let { fileSystem.createDirectories(it) }
        var detected = Layout.PAGED
        var pageCount = 0
        var firstName: String? = null
        fileSystem.write(outputPath) {
            val sink = PageSink(this)
            block(sink)
            detected = LayoutHeuristic.detectFromSizes(sampleSizes(sink.sizes, sampleSize))
            val manifest = OpzManifest(
                detectedLayout = detected.name,
                direction = direction.name,
                pages = sink.pages,
            )
            val manifestBytes =
                json.encodeToString(OpzManifest.serializer(), manifest).encodeToByteArray()
            sink.writeRaw(OpzManifest.ENTRY_NAME, manifestBytes)
            sink.finish()
            pageCount = sink.pageCount
            firstName = sink.pages.firstOrNull()?.name
        }
        return Result(pageCount, detected, firstName)
    }

    private fun sampleSizes(sizes: List<ImageSize>, sampleSize: Int): List<ImageSize> {
        if (sizes.isEmpty()) return emptyList()
        val step = maxOf(1, sizes.size / sampleSize)
        return sizes.filterIndexed { i, _ -> i % step == 0 }.take(sampleSize)
    }

    /** Recebe páginas uma a uma e as grava STORED imediatamente (memória = 1 página). */
    class PageSink internal constructor(private val out: BufferedSink) {
        private val written = ArrayList<Written>()
        internal val pages = ArrayList<OpzPage>()
        internal val sizes = ArrayList<ImageSize>()
        internal var pageCount = 0
        private var offset = 0L

        /** Emite uma página: decodifica dims (p/ manifesto e heurística), grava e descarta. */
        fun page(name: String, bytes: ByteArray) {
            val size = readImageSize(bytes)
            pages += OpzPage(name, size?.width ?: 0, size?.height ?: 0)
            if (size != null) sizes += size
            writeRaw(name, bytes)
            pageCount++
        }

        internal fun writeRaw(name: String, data: ByteArray) {
            offset += out.writeEntry(name, data, offset, written)
        }

        internal fun finish() {
            val centralStart = offset
            var centralSize = 0L
            for (e in written) centralSize += out.writeCentralHeader(e)
            out.writeEocd(written.size, centralSize, centralStart)
        }
    }

    /** Escreve um local header + dados STORED; devolve o total de bytes escritos. */
    private fun BufferedSink.writeEntry(
        name: String,
        data: ByteArray,
        offset: Long,
        acc: MutableList<Written>,
    ): Long {
        val nameBytes = name.encodeToByteArray()
        val crc = Crc32.of(data)
        writeIntLe(SIG_LOCAL)
        writeShortLe(VERSION)
        writeShortLe(FLAG_UTF8)
        writeShortLe(METHOD_STORED)
        writeShortLe(0) // mod time
        writeShortLe(0) // mod date
        writeIntLe(crc.toInt())
        writeIntLe(data.size) // compressed == uncompressed (STORED)
        writeIntLe(data.size)
        writeShortLe(nameBytes.size)
        writeShortLe(0) // extra length
        write(nameBytes)
        write(data)
        acc += Written(nameBytes, crc, data.size, offset)
        return (30 + nameBytes.size + data.size).toLong()
    }

    /** Escreve um central directory header; devolve o total de bytes escritos. */
    private fun BufferedSink.writeCentralHeader(e: Written): Long {
        writeIntLe(SIG_CENTRAL)
        writeShortLe(VERSION) // version made by
        writeShortLe(VERSION) // version needed
        writeShortLe(FLAG_UTF8)
        writeShortLe(METHOD_STORED)
        writeShortLe(0) // mod time
        writeShortLe(0) // mod date
        writeIntLe(e.crc.toInt())
        writeIntLe(e.size)
        writeIntLe(e.size)
        writeShortLe(e.nameBytes.size)
        writeShortLe(0) // extra length
        writeShortLe(0) // comment length
        writeShortLe(0) // disk number start
        writeShortLe(0) // internal attrs
        writeIntLe(0) // external attrs
        writeIntLe(e.offset.toInt()) // offset do local header
        write(e.nameBytes)
        return (46 + e.nameBytes.size).toLong()
    }

    private fun BufferedSink.writeEocd(entries: Int, centralSize: Long, centralOffset: Long) {
        writeIntLe(SIG_EOCD)
        writeShortLe(0) // disco atual
        writeShortLe(0) // disco do diretório central
        writeShortLe(entries) // entradas neste disco
        writeShortLe(entries) // entradas no total
        writeIntLe(centralSize.toInt())
        writeIntLe(centralOffset.toInt())
        writeShortLe(0) // comment length
    }
}
