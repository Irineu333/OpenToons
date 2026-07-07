package com.neoutils.opentoons.ui.library

import com.neoutils.opentoons.domain.model.Work

/**
 * Estado da tela de biblioteca. `Loading` é o inicial (evita o flash de empty state). O import
 * vive num modal próprio (ver `ui.importer`), então a biblioteca só se preocupa com listar as
 * obras — o sucesso do import chega sozinho pelo Room (Content).
 */
sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Content(val works: List<Work>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}
