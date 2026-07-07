package com.neoutils.opentoons.data.local.opz

import com.neoutils.opentoons.data.local.CbzArchive
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Leitor do `manifest.json` de um OPZ (task 2.3). Reabre o contêiner via Okio `openZip`
 * (o mesmo caminho da leitura em regime) e desserializa o manifesto com
 * kotlinx-serialization-json. A ordem/nomes de página vêm daqui — a leitura não precisa
 * re-inferir nada.
 */
object OpzReader {

    private val json = Json { ignoreUnknownKeys = true }

    /** Lê o manifesto do OPZ, ou `null` se ausente/ilegível (fallback = ordenação natural). */
    fun manifest(opzPath: String): OpzManifest? = runCatching {
        val zip = FileSystem.SYSTEM.openZip(opzPath.toPath())
        val entry = "/${OpzManifest.ENTRY_NAME}".toPath()
        if (zip.metadataOrNull(entry)?.isRegularFile != true) return null
        val bytes = zip.read(entry) { readByteArray() }
        json.decodeFromString(OpzManifest.serializer(), bytes.decodeToString())
    }.getOrNull()

    /**
     * Nomes das páginas do OPZ na ordem de leitura: do manifesto quando presente, senão a
     * ordenação natural das entradas de imagem (o `manifest.json` é ignorado por não ser imagem).
     */
    fun pageNames(opzPath: String): List<String> =
        manifest(opzPath)?.pages?.map { it.name }
            ?: CbzArchive.listImageEntries(opzPath)
}
