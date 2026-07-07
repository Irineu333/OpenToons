package com.neoutils.opentoons.data.local.rar

/**
 * `actual` iOS/Native (design D4/D5). **RAR no iOS é não-objetivo**: não há RAR em Kotlin
 * puro e o cinterop a uma lib C (`unarr`/`libunrar`) traria build nativo e licença — fora de
 * escopo. O iOS lê OPZ e importa CBZ/ZIP normalmente, e recusa qualquer RAR **por design**
 * (comportamento final, não fallback). O picker nem oferece RAR no iOS (`ImportFormats`).
 */
actual class RarArchive actual constructor(path: String) : AutoCloseable {

    init {
        // RAR (RAR4 ou RAR5) não é suportado no iOS por design — recusa com mensagem clara.
        throw UnsupportedFormatException(
            "Import de RAR/CBR não é suportado no iOS. " +
                "Importe CBZ ou ZIP; a leitura de obras já importadas segue normal.",
        )
    }

    actual fun entryNames(): List<String> = error("indisponível")

    actual fun read(name: String): ByteArray = error("indisponível")

    actual override fun close() {}
}
