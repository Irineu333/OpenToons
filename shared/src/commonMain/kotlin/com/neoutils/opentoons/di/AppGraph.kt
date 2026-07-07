package com.neoutils.opentoons.di

import com.neoutils.opentoons.data.db.OpenToonsDatabase
import com.neoutils.opentoons.data.importer.ContentImporter
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.source.SourceRegistry
import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.filesDir
import io.github.vinceglb.filekit.path
import okio.Path.Companion.toPath

/**
 * Grafo de dependências do leitor (pacote `di`, task 1.3). DI manual e leve — sem framework —
 * montado a partir do database já construído por cada plataforma (Android/Desktop/iOS).
 */
class AppGraph(database: OpenToonsDatabase) {

    val library: LibraryRepository = LibraryRepository(
        workDao = database.workDao(),
        chapterDao = database.chapterDao(),
        progressDao = database.progressDao(),
    )

    /** Registro de fontes: só `LocalImportSource` no Marco 1; `NetworkSource` entra no Marco 2. */
    val sources: SourceRegistry = SourceRegistry(listOf(LocalImportSource()))

    val importer: ContentImporter = ContentImporter(library)

    /**
     * Reconstrói a biblioteca a partir do disco (D6): varre `obras/{id}/work.json` e recria o
     * índice, preservando o estado pessoal por id. O banco vira índice reconstruível — se ele
     * for recriado (schema destrutivo, D8), o disco repovoa a biblioteca.
     */
    suspend fun rescanLibrary() {
        val obrasRoot = FileKit.filesDir.path.toPath() / "obras"
        library.rescanFromDisk(obrasRoot)
    }
}
