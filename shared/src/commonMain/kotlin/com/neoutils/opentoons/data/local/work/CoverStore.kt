package com.neoutils.opentoons.data.local.work

import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.util.CoverEncoder
import okio.FileSystem
import okio.Path

/**
 * Gera e localiza a **capa de obra** `cover.webp` (design D5, tasks 3.2/3.3). A `cover.webp` é
 * uma thumbnail **derivada (cache regenerável)** da página de capa referenciada no `work.json`
 * — resolve o custo da grade (a lista lê um arquivo pequeno em vez de destrinchar um `.opz` por
 * célula) e **não** toca nas páginas (nenhuma é transcodificada, task 3.4).
 */
object CoverStore {

    /** Nome fixo da capa dentro de `obras/{obraId}/`. */
    const val FILE_NAME = "cover.webp"

    /** Caminho da `cover.webp` na pasta da obra. */
    fun pathIn(obraDir: Path): Path = obraDir / FILE_NAME

    /**
     * (Re)gera `obras/{obraId}/cover.webp` a partir da página [entryName] do capítulo [coverOpz].
     * Retorna o caminho gravado, ou `null` se a página não decodificar (a capa é cache, o
     * chamador segue sem ela). Não altera nenhum `.opz`.
     */
    fun generate(
        fileSystem: FileSystem,
        obraDir: Path,
        coverOpz: Path,
        entryName: String,
    ): Path? {
        val pageBytes = runCatching {
            CbzArchive.readEntry(coverOpz.toString(), entryName)
        }.getOrNull() ?: return null
        return writeFromBytes(fileSystem, obraDir, pageBytes)
    }

    /**
     * Grava `obras/{obraId}/cover.webp` a partir de **bytes de imagem arbitrários** (improve-import)
     * — usada quando a capa vem de uma **imagem externa** escolhida pelo usuário, sem página de
     * origem. Retorna o caminho gravado, ou `null` se os bytes não decodificarem. Diferente da capa
     * de página, esta `cover.webp` é a **fonte durável** (não há de onde regenerá-la).
     */
    fun writeFromBytes(
        fileSystem: FileSystem,
        obraDir: Path,
        sourceBytes: ByteArray,
    ): Path? {
        val thumbnail = CoverEncoder.encodeThumbnail(sourceBytes) ?: return null
        fileSystem.createDirectories(obraDir)
        val out = pathIn(obraDir)
        fileSystem.write(out) { write(thumbnail) }
        return out
    }
}
