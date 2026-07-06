package com.neoutils.opentoons.ui.library

import com.neoutils.opentoons.domain.model.Work

/**
 * Estado da tela de biblioteca. `Loading` é o inicial (evita o flash de empty state) e também
 * cobre o import — com uma mensagem opcional para diferenciar ("Importando…").
 */
sealed interface LibraryUiState {
    data class Loading(val message: String? = null) : LibraryUiState
    data object Empty : LibraryUiState
    data class Content(val works: List<Work>) : LibraryUiState
    data class Error(val message: String) : LibraryUiState
}
