package com.neoutils.opentoons.data.local.work

import com.neoutils.opentoons.data.local.CbzArchive
import com.neoutils.opentoons.util.CoverEncoder
import okio.FileSystem
import okio.Path

/**
 * Gera e localiza a **capa de obra** `cover.webp` — uma imagem **autônoma e durável** da obra
 * (improve-import D3), reduzida a partir dos bytes da fonte escolhida no import: uma página da
 * obra ([generate]) ou uma imagem externa ([writeFromBytes]). Resolve o custo da grade (a lista lê
 * um arquivo pequeno em vez de destrinchar um `.opz` por célula) e **não** toca nas páginas
 * (nenhuma é transcodificada).
 *
 * O `work.json` guarda apenas a **proveniência** da capa, não uma referência viva: apagar o
 * capítulo de origem não invalida a `cover.webp`, e a capa externa não tem de onde ser regenerada.
 */
object CoverStore {

    /** Nome fixo da capa dentro de `obras/{obraId}/`. */
    const val FILE_NAME = "cover.webp"

    /** Caminho da `cover.webp` na pasta da obra. */
    fun pathIn(obraDir: Path): Path = obraDir / FILE_NAME

    /**
     * Grava `obras/{obraId}/cover.webp` a partir da página [entryName] do capítulo [coverOpz].
     * Retorna o caminho gravado, ou `null` se a página não decodificar (o chamador segue sem capa:
     * a obra existe mesmo sem thumbnail). Não altera nenhum `.opz`.
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
     * — reduz ao lado alvo e grava. Retorna o caminho gravado, ou `null` se os bytes não
     * decodificarem.
     */
    fun writeFromBytes(
        fileSystem: FileSystem,
        obraDir: Path,
        sourceBytes: ByteArray,
    ): Path? {
        val thumbnail = CoverEncoder.encodeThumbnail(sourceBytes) ?: return null
        return write(fileSystem, obraDir, thumbnail)
    }

    /**
     * Grava `obras/{obraId}/cover.webp` a partir de bytes **já reduzidos pelo [CoverEncoder]** —
     * é o caso da **imagem externa**, que passa pelo encoder na revisão para virar preview. Grava
     * direto, sem uma segunda passada de decode/encode (que só degradaria a imagem). Valida que os
     * bytes decodificam: retorna `null` sem gravar quando não, para a escolha do usuário não ser
     * ignorada em silêncio. Diferente da capa de página, esta `cover.webp` é a **fonte durável**
     * (não há de onde regenerá-la).
     */
    fun writeEncoded(
        fileSystem: FileSystem,
        obraDir: Path,
        thumbnailBytes: ByteArray,
    ): Path? {
        if (!CoverEncoder.isDecodable(thumbnailBytes)) return null
        return write(fileSystem, obraDir, thumbnailBytes)
    }

    private fun write(fileSystem: FileSystem, obraDir: Path, bytes: ByteArray): Path {
        fileSystem.createDirectories(obraDir)
        val out = pathIn(obraDir)
        fileSystem.write(out) { write(bytes) }
        return out
    }
}
