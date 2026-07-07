package com.neoutils.opentoons.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.di.AppGraph
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /** IDs dos capítulos selecionados (task 5.2). Vazio = fora do modo de seleção. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Mensagem de progresso/erro do fluxo de adicionar capítulos (task 5.1); null = ocioso. */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Indica que o import "dentro da obra" está em andamento (bloqueia ações concorrentes). */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun toggleFavorite() {
        viewModelScope.launch { graph.library.toggleFavorite(workUuid) }
    }

    fun setDirection(direction: ReadingDirection) {
        viewModelScope.launch { graph.library.setWorkDirection(workUuid, direction) }
    }

    fun setWorkLayoutOverride(layout: Layout?) {
        viewModelScope.launch { graph.library.setWorkLayoutOverride(workUuid, layout) }
    }

    /** Exclui a obra (linhas + pasta própria `obras/{obra}/`) e invoca [onDeleted] ao concluir. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            graph.library.deleteWork(workUuid)
            onDeleted()
        }
    }

    // --- Adicionar capítulos (task 5.1) ---

    /**
     * Adiciona capítulos a esta obra a partir de um arquivo-unidade (CBZ/CBR). Pacotes
     * (ZIP/RAR) são recusados pelo importer com mensagem clara, exposta em [status].
     */
    fun addChapters(file: PlatformFile) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Importando…"
            try {
                graph.importer.addChapters(workUuid, file) { _status.value = it }
                _status.value = null
            } catch (e: Exception) {
                _status.value = e.message ?: "Falha ao adicionar capítulos"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Descarta a mensagem de status/erro do fluxo de adicionar capítulos. */
    fun clearStatus() {
        _status.value = null
    }

    // --- Seleção e remoção de capítulos (tasks 5.2–5.4) ---

    /** Entra em modo de seleção com um capítulo (pressionar-e-segurar). */
    fun startSelection(chapterId: String) {
        _selected.value = setOf(chapterId)
    }

    /** Alterna a seleção de um capítulo (tap enquanto em modo de seleção). */
    fun toggleSelection(chapterId: String) {
        val current = _selected.value
        _selected.value = if (chapterId in current) current - chapterId else current + chapterId
    }

    /** Sai do modo de seleção. */
    fun clearSelection() {
        _selected.value = emptySet()
    }

    /**
     * Remove os capítulos selecionados (apaga `.opz` + progresso, reindexa e trata capa órfã).
     * Se a obra ficar sem capítulos, invoca [onWorkEmptied] (o chamador confirma remover a obra).
     */
    fun removeSelected(onWorkEmptied: () -> Unit) {
        val ids = _selected.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val remaining = graph.library.deleteChapters(workUuid, ids)
            _selected.value = emptySet()
            if (remaining == 0) onWorkEmptied()
        }
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
