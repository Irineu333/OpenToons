package com.neoutils.opentoons.data.local.opz

import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.util.ImageSize
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.FileSystem
import okio.Path

/** Página a gravar no OPZ: nome plano da entrada, bytes e dimensões (para o manifesto). */
class OpzPageInput(
    val name: String,
    val bytes: ByteArray,
    val size: ImageSize? = null,
)

/**
 * Escritor OPZ pura-Kotlin (design D6, task 2.2): grava um contêiner ZIP com entradas
 * **STORED** (sem compressão) — local header + CRC-32 + bytes crus, diretório central e EOCD —
 * mais o `manifest.json` (task 2.1). STORED dá leitura zero-copy na leitura em regime e
 * dispensa qualquer dependência nativa de *escrita*: o `openZip` do Okio reabre o resultado.
 *
 * Imagens já vêm comprimidas (jpg/webp/png); re-DEFLATE ganharia ~1% e custaria CPU em toda
 * leitura de página — por isso STORED (D1). Os offsets do diretório central são rastreados
 * manualmente conforme os bytes vão para o `Okio.Sink` (não há posição no sink).
 */
object OpzWriter {

    private val json = Json { encodeDefaults = true }

    private const val SIG_LOCAL = 0x04034b50
    private const val SIG_CENTRAL = 0x02014b50
    private const val SIG_EOCD = 0x06054b50
    private const val VERSION = 20
    private const val FLAG_UTF8 = 0x0800 // bit 11: nomes de entrada em UTF-8
    private const val METHOD_STORED = 0

    /** Entrada já resolvida (nome UTF-8, CRC, tamanho, offset do local header). */
    private class Written(val nameBytes: ByteArray, val crc: Long, val size: Int, val offset: Long)

    /**
     * Grava o OPZ em [outputPath]: as [pages] STORED seguidas do `manifest.json`. O manifesto
     * é montado a partir dos nomes/dimensões das páginas + [detectedLayout]/[direction].
     */
    fun write(
        fileSystem: FileSystem,
        outputPath: Path,
        pages: List<OpzPageInput>,
        detectedLayout: Layout,
        direction: ReadingDirection,
    ) {
        val manifest = OpzManifest(
            detectedLayout = detectedLayout.name,
            direction = direction.name,
            pages = pages.map { OpzPage(it.name, it.size?.width ?: 0, it.size?.height ?: 0) },
        )
        val manifestBytes = json.encodeToString(OpzManifest.serializer(), manifest).encodeToByteArray()

        outputPath.parent?.let { fileSystem.createDirectories(it) }
        fileSystem.write(outputPath) {
            val written = ArrayList<Written>(pages.size + 1)
            var offset = 0L
            // Páginas primeiro, manifesto por último (ordem irrelevante para o ZIP).
            for (page in pages) offset += writeEntry(page.name, page.bytes, offset, written)
            offset += writeEntry(OpzManifest.ENTRY_NAME, manifestBytes, offset, written)

            val centralStart = offset
            var centralSize = 0L
            for (e in written) centralSize += writeCentralHeader(e)
            writeEocd(written.size, centralSize, centralStart)
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
