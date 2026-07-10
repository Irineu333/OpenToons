package com.neoutils.opentoons.util

/**
 * Extensões de import oferecidas no seletor de arquivo, por plataforma. CBR/RAR só entram
 * onde a descompactação RAR existe ([rarImportSupported]) — no iOS RAR é **não-objetivo**,
 * então o picker não sugere formatos que sempre falhariam (D4/D5).
 */
object ImportFormats {

    /** Biblioteca: unidade (cbz[/cbr]) + pacote (zip[/rar]). */
    val library: List<String> = buildList {
        add("cbz")
        add("zip")
        if (rarImportSupported) {
            add("cbr")
            add("rar")
        }
    }

    /** Dentro da obra: só arquivos-unidade (cbz[/cbr]); pacotes não entram na obra. */
    val unit: List<String> = buildList {
        add("cbz")
        if (rarImportSupported) add("cbr")
    }

    /**
     * Imagens aceitas como **capa externa** na revisão (improve-import). Usamos
     * `FileKitType.File(coverImages)` em vez de `FileKitType.Image` porque o picker de galeria
     * nativo filtra por um conjunto fixo de tipos que, em algumas plataformas, **exclui webp** —
     * e webp é justamente o formato da capa gerada pelo app.
     */
    val coverImages: List<String> = listOf("webp", "png", "jpg", "jpeg", "gif", "bmp")

    /** Rótulo legível dos formatos de biblioteca, ex.: "CBZ, ZIP, CBR ou RAR" (ou "CBZ ou ZIP"). */
    val libraryLabel: String = label(library)

    private fun label(extensions: List<String>): String {
        val names = extensions.map { it.uppercase() }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " ou " + names.last()
        }
    }
}
