package com.neoutils.opentoons.data.local.rar

/**
 * `actual` iOS/Native (task 3.3). O caminho definitivo é o cinterop `unarr` (RAR4), que
 * depende do **spike 1.1** (static lib `iosArm64`/`iosSimulatorArm64` + `.def`). Enquanto o
 * spike não fecha, aplica-se o **fallback documentado (design D5)**: RAR é recusado no iOS com
 * mensagem clara — a degradação fica isolada nessa plataforma, sem afetar a leitura de OPZ nem
 * o import de CBZ/ZIP. Ao integrar o `unarr`, só este `actual` muda.
 */
actual class RarArchive actual constructor(path: String) : AutoCloseable {

    init {
        // No iOS nenhum RAR é suportado (nem RAR4 nem RAR5) enquanto o cinterop `unarr` não
        // fecha. Não diferencia a variante para não sugerir RAR4 (que também não roda aqui):
        // recusa qualquer RAR com uma mensagem específica da plataforma.
        throw UnsupportedFormatException(
            "Import de RAR/CBR ainda não disponível no iOS (suporte a RAR pendente). " +
                "Importe CBZ ou ZIP; a leitura de obras já importadas segue normal.",
        )
    }

    actual fun entryNames(): List<String> = error("indisponível")

    actual fun read(name: String): ByteArray = error("indisponível")

    actual override fun close() {}
}
