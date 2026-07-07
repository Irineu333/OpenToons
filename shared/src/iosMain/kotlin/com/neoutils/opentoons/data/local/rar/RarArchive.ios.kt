package com.neoutils.opentoons.data.local.rar

/**
 * `actual` iOS/Native (task 3.3). O caminho definitivo é o cinterop `unarr` (RAR4), que
 * depende do **spike 1.1** (buildar a static lib para `iosArm64`/`iosSimulatorArm64` + `.def`).
 * Enquanto o spike não fecha, aplica-se o **fallback documentado (design D5)**: RAR é recusado
 * no iOS com mensagem clara — a degradação fica isolada nessa plataforma, sem afetar a leitura
 * de OPZ nem o import de CBZ/ZIP. Ao integrar o `unarr`, só este `actual` muda.
 */
actual object RarArchive {
    actual fun extractAll(path: String): List<RarEntry> {
        // Recusa RAR5 com a mesma mensagem das demais plataformas, por consistência.
        RarFormat.requireNotRar5(path)
        throw UnsupportedFormatException(
            "Import de RAR/CBR ainda não disponível no iOS (cinterop unarr pendente — spike 1.1). " +
                "Importe CBZ/ZIP; a leitura de obras já importadas segue normal.",
        )
    }
}
