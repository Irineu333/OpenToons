package com.neoutils.opentoons.util

/**
 * Extensões de import oferecidas no seletor de arquivo, por plataforma. CBR/RAR só entram
 * onde a descompactação RAR existe ([rarImportSupported]) — no iOS, enquanto o cinterop
 * `unarr` não fecha (spike 1.1), o picker não sugere formatos que sempre falhariam (D5).
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
}
