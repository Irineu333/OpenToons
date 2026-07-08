package com.neoutils.opentoons.data.local.work

import kotlinx.serialization.Serializable

/**
 * Manifesto de obra (`work.json`, design D2) — espelho local da camada `obra.meta` do
 * ADR-0003 e **fonte de verdade dos dados intrínsecos da obra**: `title`, `direction`
 * detectada e qual página é a capa. Vive em `obras/{obraId}/work.json`, ao lado dos `.opz`
 * dos capítulos (sidecar, D1).
 *
 * Campos assinados (`chavePublicador`, assinatura, `seq`) ficam **previstos e nulos** neste
 * marco — assinatura é Marco 2. Forward-compatible: no Marco 2, `cover` passa a referenciar
 * **CID** e `chavePublicador` deixa de ser nulo. A **ordem dos capítulos** não é gravada: é o
 * *natural sort* dos nomes dos `.opz` (mesma regra do import), então não precisa de lista aqui.
 */
@Serializable
data class WorkManifest(
    val version: Int = FORMAT_VERSION,
    val obraId: String,
    val chavePublicador: String? = null,
    val title: String,
    // Texto livre editável na revisão do import (opcional). Forward-compatible: manifestos
    // antigos sem o campo desserializam para vazio (default).
    val description: String = "",
    val direction: String,
    val cover: WorkCover? = null,
) {
    companion object {
        const val FORMAT_VERSION = 1

        /** Nome fixo do manifesto de obra dentro de `obras/{obraId}/`. */
        const val FILE_NAME = "work.json"
    }
}

/**
 * **Proveniência** da capa da obra (improve-import) — a capa é uma **imagem autônoma**
 * (`cover.webp`), não uma referência viva a uma página. Este registro apenas descreve **de onde**
 * a capa veio: de uma [CoverSource.PAGE] (`{chapterId, entryName}` da página extraída) ou de uma
 * imagem [CoverSource.EXTERNAL] importada pelo usuário. Apagar o capítulo de origem **não**
 * invalida a capa. Forward-compatible: manifestos antigos sem `source` desserializam como `PAGE`
 * (o formato anterior era só `{chapterId, entryName}`). Vira CID no Marco 2.
 */
@Serializable
data class WorkCover(
    val source: CoverSource = CoverSource.PAGE,
    val chapterId: String? = null,
    val entryName: String? = null,
) {
    companion object {
        /** Capa extraída de uma página da obra (default). */
        fun page(chapterId: String, entryName: String) =
            WorkCover(CoverSource.PAGE, chapterId, entryName)

        /** Capa vinda de uma imagem externa (sem referência de página). */
        fun external() = WorkCover(CoverSource.EXTERNAL)
    }
}

/** Origem da capa: uma página da própria obra ou uma imagem externa importada. */
@Serializable
enum class CoverSource { PAGE, EXTERNAL }
