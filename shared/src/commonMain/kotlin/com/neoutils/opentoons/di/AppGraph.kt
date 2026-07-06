package com.neoutils.opentoons.di

import com.neoutils.opentoons.data.db.OpenToonsDatabase
import com.neoutils.opentoons.data.importer.CbzImporter
import com.neoutils.opentoons.data.repository.LibraryRepository
import com.neoutils.opentoons.data.source.LocalImportSource
import com.neoutils.opentoons.domain.source.SourceRegistry

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

    val importer: CbzImporter = CbzImporter(library)
}
