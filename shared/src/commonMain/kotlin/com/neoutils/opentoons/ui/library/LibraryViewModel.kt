package com.neoutils.opentoons.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.di.AppGraph
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class LibraryViewModel(private val graph: AppGraph) : ViewModel() {

    // Um único estado: Loading (carga inicial e import), Empty, Content e Error. O collector do
    // Room emite Empty/Content/Error; o import emite Loading e o sucesso volta via Room (Content).
    private val _uiState = MutableStateFlow<LibraryUiState>(LibraryUiState.Loading())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        graph.library.observeLibrary()
            .map { works ->
                if (works.isEmpty()) LibraryUiState.Empty else LibraryUiState.Content(works)
            }
            .catch { emit(LibraryUiState.Error(it.message ?: "Erro ao carregar a biblioteca")) }
            .onEach { _uiState.value = it }
            .launchIn(viewModelScope)
    }

    fun import(file: PlatformFile) {
        viewModelScope.launch {
            _uiState.value = LibraryUiState.Loading("Importando…")
            try {
                // Progresso do import (task 4.6): RAR + re-zip STORED são mais lentos que o
                // copy-in intacto do Marco 1; a UI reflete a etapa/capítulo em conversão.
                graph.importer.importWork(file) { message ->
                    _uiState.value = LibraryUiState.Loading(message)
                }
                // Sucesso: o Room emite a nova lista (Content) pelo collector do init.
            } catch (e: Exception) {
                _uiState.value = LibraryUiState.Error(e.message ?: "Falha ao importar")
            }
        }
    }

    fun toggleFavorite(uuid: String) {
        viewModelScope.launch { graph.library.toggleFavorite(uuid) }
    }
}
