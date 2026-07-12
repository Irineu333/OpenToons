package com.neoutils.opentoons.data.local

import com.neoutils.opentoons.util.NaturalOrder
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Leitura de `.cbz`/`.zip` via Okio `openZip` (design D5, spec content-import). Roda em
 * JVM/Android/Native (iOS) e cobre STORED+DEFLATE. Lê o diretório central e serve cada
 * entrada **sob demanda**, sem descompactar o capítulo inteiro — memória limitada.
 */
object CbzArchive {

    private val imageExtensions = setOf(
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "avif", "jxl", "heic",
    )

    /** Capítulo detectado dentro do arquivo: um diretório (vazio = raiz) e suas páginas. */
    data class ArchiveChapter(val dir: String, val entries: List<String>)

    /** Todas as entradas de imagem do arquivo, na ordenação natural dos nomes (task 3.5). */
    fun listImageEntries(archivePath: String): List<String> {
        val zip = FileSystem.SYSTEM.openZip(archivePath.toPath())
        val root = "/".toPath()
        return zip.listRecursively(root)
            .filter { zip.metadataOrNull(it)?.isRegularFile == true }
            .map { it.toString().removePrefix("/") }
            .filter { it.substringAfterLast('.', "").lowercase() in imageExtensions }
            .toList()
            .sortedWith(NaturalOrder)
    }

    /** Entradas de imagem de um diretório específico (parent == [dir]), ordenadas. */
    fun listImageEntries(archivePath: String, dir: String): List<String> =
        listImageEntries(archivePath)
            .filter { it.substringBeforeLast('/', "") == dir }
            .sortedWith(NaturalOrder)

    /**
     * Agrupa as imagens por diretório-pai — cada pasta com imagens vira um capítulo (spec
     * content-import). Um CBZ "plano" (imagens na raiz) resulta em um único grupo (dir vazio);
     * um CBZ com pastas de capítulos resulta em um grupo por pasta, em ordem natural.
     */
    fun chapters(archivePath: String): List<ArchiveChapter> =
        listImageEntries(archivePath)
            .groupBy { it.substringBeforeLast('/', "") }
            .map { (dir, entries) -> ArchiveChapter(dir, entries.sortedWith(NaturalOrder)) }
            .sortedWith { a, b -> NaturalOrder.compare(a.dir, b.dir) }

    /** Lê os bytes de uma entrada, sob demanda. */
    fun readEntry(archivePath: String, entryName: String): ByteArray {
        val zip = FileSystem.SYSTEM.openZip(archivePath.toPath())
        val path = "/$entryName".toPath()
        return zip.read(path) { readByteArray() }
    }

    /**
     * Lê no máximo [maxBytes] do início de uma entrada — o suficiente para o sniff de header
     * (task 1.6), sem materializar a página inteira (tiras de webtoon podem ser dezenas de MB).
     */
    fun readEntryHeader(archivePath: String, entryName: String, maxBytes: Int = 64 * 1024): ByteArray {
        val zip = FileSystem.SYSTEM.openZip(archivePath.toPath())
        val path = "/$entryName".toPath()
        return zip.read(path) {
            val out = okio.Buffer()
            while (out.size < maxBytes) {
                val n = read(out, maxBytes - out.size)
                if (n == -1L) break
            }
            out.readByteArray()
        }
    }
}
