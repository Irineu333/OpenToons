package com.neoutils.opentoons.ui.importer

import com.neoutils.opentoons.data.importer.ImportDraft

/**
 * Estado do **modal de import** (edit-import-metadata): um fluxo próprio, isolado da biblioteca.
 * `Hidden` = fechado; `Loading` cobre o processamento (ler arquivo / salvar) com mensagem;
 * `Reviewing` mostra o formulário de revisão (título/descrição/capa); `Error` a falha.
 */
sealed interface ImportUiState {
    data object Hidden : ImportUiState
    data class Loading(val message: String) : ImportUiState
    data class Reviewing(val draft: ImportDraft) : ImportUiState
    data class Error(val message: String) : ImportUiState
}
