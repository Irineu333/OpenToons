package com.neoutils.opentoons.data.local.opz

import kotlinx.serialization.Serializable

/**
 * Manifesto de um capítulo OPZ (design D7). Carrega a **ordem/nome das páginas**, o
 * `detectedLayout` e as dimensões por página (evita re-sniff na leitura), mais o `chapterId`
 * interno estável (D3).
 *
 * `chapterId` (uuid) é a **chave estável de estado** do capítulo, independente do nome do
 * `.opz` (que é só o título/ordem, cosmético) — o progresso é casado por ele, sobrevive a
 * rename (D3). A `direction` **não** vive aqui: subiu para a obra (`work.json`), por ser
 * intrínseca da obra e não do capítulo (D4). O `detectedLayout` permanece no capítulo — a
 * *detecção* mora no capítulo (páginas variam de formato); a *direção* é da obra.
 *
 * Os campos do ADR-0003 (`obraId`, `chavePublicador`) ficam **previstos e nulos/reservados**
 * neste marco — o OPZ é a fatia por-capítulo do manifesto assinado do Marco 2, materializada
 * em disco. O hash sha-256 por página fica **deferido** (D7); como as entradas são STORED
 * (bytes estáveis), calcular blocos/CID depois é quase de graça.
 */
@Serializable
data class OpzManifest(
    val version: Int = FORMAT_VERSION,
    val obraId: String? = null,
    val chavePublicador: String? = null,
    val chapterId: String? = null,
    val detectedLayout: String,
    val pages: List<OpzPage>,
) {
    companion object {
        const val FORMAT_VERSION = 1

        /** Nome fixo da entrada do manifesto dentro do contêiner OPZ. */
        const val ENTRY_NAME = "manifest.json"
    }
}

/** Página do capítulo: nome da entrada (plano) dentro do OPZ e suas dimensões (0 = desconhecida). */
@Serializable
data class OpzPage(
    val name: String,
    val width: Int = 0,
    val height: Int = 0,
)
