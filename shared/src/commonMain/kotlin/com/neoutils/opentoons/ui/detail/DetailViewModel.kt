package com.neoutils.opentoons.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.di.AppGraph
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailViewModel(
    private val graph: AppGraph,
    private val workUuid: String,
) : ViewModel() {

    val work: StateFlow<Work?> = graph.library.observeWork(workUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val chapters: StateFlow<List<Chapter>> = graph.library.observeChapters(workUuid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleFavorite() {
        viewModelScope.launch { graph.library.toggleFavorite(workUuid) }
    }

    fun setDirection(direction: ReadingDirection) {
        viewModelScope.launch { graph.library.setWorkDirection(workUuid, direction) }
    }

    fun setWorkLayoutOverride(layout: Layout?) {
        viewModelScope.launch { graph.library.setWorkLayoutOverride(workUuid, layout) }
    }

    /**
     * Resolve o capítulo para "continuar leitura": o primeiro não concluído com progresso,
     * senão o primeiro capítulo. `onResolved(null)` quando não há capítulos.
     */
    fun continueReading(onResolved: (Chapter?) -> Unit) {
        viewModelScope.launch {
            val list = graph.library.observeChapters(workUuid).first()
            val target = list.firstOrNull { !it.read } ?: list.firstOrNull()
            onResolved(target)
        }
    }
}
