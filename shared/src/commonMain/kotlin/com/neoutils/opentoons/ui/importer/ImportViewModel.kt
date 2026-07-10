package com.neoutils.opentoons.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.data.importer.CoverCandidate
import com.neoutils.opentoons.data.importer.CoverChoice
import com.neoutils.opentoons.data.importer.ImportDraft
import com.neoutils.opentoons.data.importer.ImportEdits
import com.neoutils.opentoons.di.AppGraph
import com.neoutils.opentoons.util.CoverEncoder
import com.neoutils.opentoons.util.ioDispatcher
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel do modal de import: dono do fluxo em duas fases e de todo o estado da revisão.
 * [start] prepara a origem (Fase A) e abre o formulário; as ações de edição atualizam o
 * [ImportForm]; [confirm] materializa (Fase B); [cancel] descarta a origem sem deixar rastro.
 *
 * O [draft] (origem retida) vive num flow próprio porque sobrevive às transições de estado —
 * `Reviewing → Loading → Error` — e precisa ser descartado em qualquer uma delas. O sucesso não é
 * notificado à biblioteca: o `LibraryViewModel` observa o Room e reflete a nova obra sozinho.
 */
class ImportViewModel(private val graph: AppGraph) : ViewModel() {

    private val draft = MutableStateFlow<ImportDraft?>(null)

    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Hidden)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun start(file: PlatformFile) {
        viewModelScope.launch {
            _state.value = ImportUiState.Loading("Lendo arquivo…")
            runCatching { graph.importer.prepare(file) }
                .onSuccess { prepared ->
                    draft.value = prepared
                    _state.value = ImportUiState.Reviewing(prepared.toForm())
                }
                .onFailure { error ->
                    draft.value = null
                    _state.value = ImportUiState.Error(error.message ?: "Falha ao ler o arquivo")
                }
        }
    }

    fun updateTitle(title: String) = updateForm { it.copy(title = title) }

    fun updateDescription(description: String) = updateForm { it.copy(description = description) }

    fun selectPageCover(candidate: CoverCandidate) = updateForm {
        it.copy(
            cover = CoverChoice.Page(candidate.chapterId, candidate.entryName),
            coverError = null,
        )
    }

    /** Seleciona a imagem externa já carregada, sem reabrir o seletor. */
    fun selectExternalCover() = updateForm { form ->
        val loaded = form.externalCover ?: return@updateForm form
        form.copy(cover = CoverChoice.External(loaded), coverError = null)
    }

    /** Lê e reduz a imagem escolhida fora da main thread; sinaliza no formulário se não decodificar. */
    fun loadExternalCover(file: PlatformFile) {
        viewModelScope.launch {
            val thumbnail = withContext(ioDispatcher) {
                runCatching { CoverEncoder.encodeThumbnail(file.readBytes()) }.getOrNull()
            }
            updateForm { form ->
                if (thumbnail == null) {
                    form.copy(coverError = COVER_READ_ERROR)
                } else {
                    form.copy(
                        cover = CoverChoice.External(thumbnail),
                        externalCover = thumbnail,
                        coverError = null,
                    )
                }
            }
        }
    }

    /** Fecha o aviso de imagem ilegível; o formulário volta com a capa anterior intacta. */
    fun dismissCoverError() = updateForm { it.copy(coverError = null) }

    fun confirm() {
        val current = draft.value ?: return
        val edits = (_state.value as? ImportUiState.Reviewing)?.form?.toEdits() ?: return
        viewModelScope.launch {
            _state.value = ImportUiState.Loading("Importando…")
            runCatching {
                graph.importer.commit(current, edits) { message ->
                    _state.value = ImportUiState.Loading(message)
                }
            }.onSuccess {
                draft.value = null
                _state.value = ImportUiState.Hidden
            }.onFailure { error ->
                _state.value = ImportUiState.Error(error.message ?: "Falha ao importar")
            }
        }
    }

    fun cancel() {
        val discarded = draft.value
        draft.value = null
        _state.value = ImportUiState.Hidden
        if (discarded != null) {
            viewModelScope.launch { runCatching { graph.importer.cancel(discarded) } }
        }
    }

    private fun updateForm(transform: (ImportForm) -> ImportForm) {
        _state.update { state ->
            if (state is ImportUiState.Reviewing) ImportUiState.Reviewing(transform(state.form)) else state
        }
    }

    private companion object {
        const val COVER_READ_ERROR = "Não foi possível ler essa imagem. Tente PNG ou JPEG."
    }
}

private fun ImportDraft.toForm() = ImportForm(
    candidates = coverCandidates,
    title = defaultTitle,
    description = "",
    cover = defaultCover,
)

private fun ImportForm.toEdits() = ImportEdits(
    title = title,
    description = description,
    cover = cover,
)
