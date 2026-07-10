package com.neoutils.opentoons.ui.importer

import com.neoutils.opentoons.data.importer.CoverCandidate
import com.neoutils.opentoons.data.importer.CoverChoice

/**
 * Estado do modal de import: `Hidden` = fechado; `Loading` cobre o processamento (ler arquivo /
 * salvar); `Reviewing` carrega o [ImportForm] editável; `Error` a falha.
 */
sealed interface ImportUiState {
    data object Hidden : ImportUiState
    data class Loading(val message: String) : ImportUiState
    data class Reviewing(val form: ImportForm) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

/**
 * Formulário da revisão, dono do estado editável (o composable é stateless). [externalCover] é a
 * thumbnail da imagem externa escolhida — preservada mesmo quando o usuário volta a uma página,
 * para a célula não perder o preview. [coverError] preenchido levanta o modal de aviso de imagem
 * ilegível sobre a revisão.
 */
data class ImportForm(
    val candidates: List<CoverCandidate>,
    val title: String,
    val description: String,
    val cover: CoverChoice?,
    val externalCover: ByteArray? = null,
    val coverError: String? = null,
)
