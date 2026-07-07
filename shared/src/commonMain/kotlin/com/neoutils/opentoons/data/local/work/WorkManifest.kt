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
 * Identidade da capa (dado, fiel): aponta uma **página real** dentro de um capítulo pelo
 * `chapterId` interno e o nome da entrada. Vira CID no Marco 2. Distinta da `cover.webp`, que
 * é a thumbnail derivada/regenerável (cache de UI, D5).
 */
@Serializable
data class WorkCover(
    val chapterId: String,
    val entryName: String,
)
