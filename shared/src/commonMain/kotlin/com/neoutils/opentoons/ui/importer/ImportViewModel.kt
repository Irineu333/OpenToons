package com.neoutils.opentoons.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.data.importer.ImportDraft
import com.neoutils.opentoons.data.importer.ImportEdits
import com.neoutils.opentoons.di.AppGraph
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel do **modal de import** (edit-import-metadata): dono do fluxo em duas fases, isolado
 * da biblioteca. [start] abre o processamento da origem (Fase A, [ContentImporter.prepare]) e
 * exibe a revisão; [confirm] materializa com os valores editados (Fase B,
 * [ContentImporter.commit]) e fecha; [cancel]/[dismiss] descartam a origem sem deixar rastro.
 * O sucesso não é notificado à biblioteca — o `LibraryViewModel` observa o Room e reflete a
 * nova obra sozinho.
 */
class ImportViewModel(private val graph: AppGraph) : ViewModel() {

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Hidden)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    // Origem retida da revisão em curso, para descartar no cancel.
    private var draft: ImportDraft? = null

    /** Fase A: processa o arquivo escolhido e abre a revisão (nada gravado ainda). */
    fun start(file: PlatformFile) {
        viewModelScope.launch {
            _state.value = ImportUiState.Loading("Lendo arquivo…")
            try {
                val prepared = graph.importer.prepare(file)
                draft = prepared
                _state.value = ImportUiState.Reviewing(prepared)
            } catch (e: Exception) {
                draft = null
                _state.value = ImportUiState.Error(e.message ?: "Falha ao ler o arquivo")
            }
        }
    }

    /** Fase B: materializa a obra com os valores editados e fecha o modal. */
    fun confirm(edits: ImportEdits) {
        val current = draft ?: return
        viewModelScope.launch {
            _state.value = ImportUiState.Loading("Importando…")
            try {
                graph.importer.commit(current, edits) { message ->
                    _state.value = ImportUiState.Loading(message)
                }
                draft = null
                _state.value = ImportUiState.Hidden // a biblioteca atualiza via Room
            } catch (e: Exception) {
                _state.value = ImportUiState.Error(e.message ?: "Falha ao importar")
            }
        }
    }

    /** Cancela a revisão (ou dispensa o erro): descarta a origem retida e fecha (nada gravado). */
    fun cancel() {
        val current = draft
        draft = null
        _state.value = ImportUiState.Hidden
        if (current != null) {
            viewModelScope.launch { runCatching { graph.importer.cancel(current) } }
        }
    }
}
