package com.neoutils.opentoons.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.di.AppGraph
import com.neoutils.opentoons.domain.model.Work
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(private val graph: AppGraph) : ViewModel() {

    val works: StateFlow<List<Work>> = graph.library.observeLibrary()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun import(file: PlatformFile) {
        viewModelScope.launch {
            _importing.value = true
            _error.value = null
            try {
                graph.importer.importFrom(file)
            } catch (e: Exception) {
                _error.value = e.message ?: "Falha ao importar"
            } finally {
                _importing.value = false
            }
        }
    }

    fun toggleFavorite(uuid: String) {
        viewModelScope.launch { graph.library.toggleFavorite(uuid) }
    }

    fun clearError() {
        _error.value = null
    }
}
