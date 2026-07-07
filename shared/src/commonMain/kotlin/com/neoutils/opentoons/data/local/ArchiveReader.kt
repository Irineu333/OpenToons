package com.neoutils.opentoons.data.local

import com.neoutils.opentoons.data.local.rar.RarArchive
import com.neoutils.opentoons.data.local.rar.UnsupportedFormatException
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Contêiner de origem já aberto (task 4.1): lista as entradas-arquivo e serve os bytes por
 * nome. Abstrai ZIP (Okio `openZip`, sob demanda) e RAR (`RarArchive`, extração por-entrada —
 * D5) atrás de um contrato único para o pipeline de import.
 */
interface ArchiveReader : AutoCloseable {
    /** Nomes de todas as entradas-arquivo (caminho relativo, `/` como separador). */
    fun entryNames(): List<String>

    /** Bytes de uma entrada. */
    fun read(name: String): ByteArray

    /** Libera recursos (o RAR mantém o arquivo aberto; ZIP é sem estado). */
    override fun close() {}

    companion object {
        private val IMAGE_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jxl", "heic",
        )
        private val ARCHIVE_EXTENSIONS = setOf("cbz", "cbr")

        fun isImage(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

        fun isArchive(name: String): Boolean =
            name.substringAfterLast('.', "").lowercase() in ARCHIVE_EXTENSIONS

        /**
         * Desambiguação unidade vs pacote (D3): um container é **pacote** quando tem
         * arquivos-arquivo (`.cbz`/`.cbr`) **e nenhuma imagem** — cada arquivo interno é um
         * capítulo. Se há imagens, é **unidade** (as imagens são o conteúdo), mesmo que haja
         * um `.cbz`/`.cbr` perdido entre elas (lixo de empacotamento) — o predicado por
         * presença de imagem evita classificar mal um CBZ com arquivos aninhados espúrios.
         */
        fun isPackage(entries: List<String>): Boolean {
            val hasImages = entries.any { isImage(it) }
            val hasArchives = entries.any { isArchive(it) }
            return hasArchives && !hasImages
        }
    }
}

/** Leitor de contêiner ZIP (CBZ/ZIP) via Okio `openZip` — leitura sob demanda. */
class ZipArchiveReader(private val path: String) : ArchiveReader {
    override fun entryNames(): List<String> {
        val zip = FileSystem.SYSTEM.openZip(path.toPath())
        return zip.listRecursively("/".toPath())
            .filter { zip.metadataOrNull(it)?.isRegularFile == true }
            .map { it.toString().removePrefix("/") }
            .toList()
    }

    override fun read(name: String): ByteArray = CbzArchive.readEntry(path, name)
}

/**
 * Leitor de contêiner RAR (CBR/RAR) via [RarArchive]: lista os nomes e extrai cada entrada
 * **por-entrada, sob demanda** (mantém o arquivo aberto — precisa de [close]). RAR5 é recusado por
 * [RarArchive]. Usar dentro de `use { }` para fechar o handle.
 */
class RarArchiveReader(path: String) : ArchiveReader {
    private val archive = RarArchive(path)

    override fun entryNames(): List<String> = archive.entryNames()

    override fun read(name: String): ByteArray = archive.read(name)

    override fun close() = archive.close()
}

/**
 * Formato de contêiner detectado por **magic bytes** (mais robusto que a extensão): ZIP
 * (`PK`) para CBZ/ZIP, RAR (`Rar!`) para CBR/RAR.
 */
enum class ContainerFormat { ZIP, RAR }

object ContainerFormats {

    /** Detecta o formato pela assinatura do arquivo; lança se não for ZIP nem RAR. */
    fun detect(path: String): ContainerFormat {
        val head = FileSystem.SYSTEM.read(path.toPath()) {
            val buffer = ByteArray(4)
            var read = 0
            while (read < buffer.size) {
                val n = read(buffer, read, buffer.size - read)
                if (n == -1) break
                read += n
            }
            buffer.copyOf(read)
        }
        return when {
            head.size >= 2 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() -> ContainerFormat.ZIP
            head.size >= 4 && head[0] == 0x52.toByte() && head[1] == 0x61.toByte() &&
                head[2] == 0x72.toByte() && head[3] == 0x21.toByte() -> ContainerFormat.RAR
            else -> throw UnsupportedFormatException(
                "Arquivo não reconhecido como ZIP (CBZ/ZIP) nem RAR (CBR/RAR).",
            )
        }
    }

    /** Abre o leitor apropriado para o [path], detectando o formato. */
    fun open(path: String): ArchiveReader = when (detect(path)) {
        ContainerFormat.ZIP -> ZipArchiveReader(path)
        ContainerFormat.RAR -> RarArchiveReader(path)
    }
}
