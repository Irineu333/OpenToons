package com.neoutils.opentoons.data.local.work

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * Writer/reader do `work.json` (task 1.2), análogo a `OpzReader`/`OpzWriter`: serializa o
 * [WorkManifest] com kotlinx-serialization-json e o grava/lê via Okio. É a porta única para o
 * manifesto de obra em disco — a fonte de verdade dos dados da obra (D2/D6).
 */
object WorkManifestStore {

    private val writeJson = Json { encodeDefaults = true; prettyPrint = true }
    private val readJson = Json { ignoreUnknownKeys = true }

    /** Caminho do manifesto dentro da pasta da obra. */
    fun pathIn(obraDir: Path): Path = obraDir / WorkManifest.FILE_NAME

    /** Grava o `work.json` em `obras/{obraId}/` (cria o diretório se preciso). */
    fun write(fileSystem: FileSystem, obraDir: Path, manifest: WorkManifest) {
        fileSystem.createDirectories(obraDir)
        val bytes = writeJson.encodeToString(WorkManifest.serializer(), manifest).encodeToByteArray()
        fileSystem.write(pathIn(obraDir)) { write(bytes) }
    }

    /** Lê o `work.json`, ou `null` se ausente/ilegível. */
    fun read(fileSystem: FileSystem, obraDir: Path): WorkManifest? = runCatching {
        val path = pathIn(obraDir)
        if (fileSystem.metadataOrNull(path)?.isRegularFile != true) return null
        val bytes = fileSystem.read(path) { readByteArray() }
        readJson.decodeFromString(WorkManifest.serializer(), bytes.decodeToString())
    }.getOrNull()
}
