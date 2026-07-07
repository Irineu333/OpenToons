package com.neoutils.opentoons.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.neoutils.opentoons.di.AppGraph
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.domain.model.ChapterProgress
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.Page
import com.neoutils.opentoons.domain.model.ReadingDirection
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.domain.model.effectiveLayout
import com.neoutils.opentoons.util.nowMillis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class ReaderState(
    val loading: Boolean = true,
    val chapter: Chapter? = null,
    val work: Work? = null,
    val layout: Layout = Layout.PAGED,
    val direction: ReadingDirection = ReadingDirection.LTR,
    val pages: List<Page> = emptyList(),
    val initialPage: Int = 0,
    val initialScroll: Float = 0f,
    val prevChapterId: String? = null,
    val nextChapterId: String? = null,
    val error: String? = null,
)

class ReaderViewModel(
    private val graph: AppGraph,
    private val chapterId: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    init { load() }

    private fun load() {
        viewModelScope.launch {
            try {
                val chapter = graph.library.chapter(chapterId)
                if (chapter == null) {
                    _state.value = ReaderState(loading = false, error = "Capítulo não encontrado")
                    return@launch
                }
                val work = graph.library.work(chapter.workUuid)
                // Precedência do layout efetivo (D6 / task 5.2).
                val layout = effectiveLayout(
                    chapterOverride = chapter.layoutOverride,
                    workOverride = work?.layoutOverride,
                    detected = chapter.detectedLayout,
                )
                val direction = work?.effectiveDirection ?: ReadingDirection.LTR
                val pages = graph.sources.forChapter(chapter).pages(chapter)
                val progress = graph.library.progress(chapterId)
                val siblings = graph.library.observeChapters(chapter.workUuid).first()
                val here = siblings.indexOfFirst { it.id == chapterId }
                _state.value = ReaderState(
                    loading = false,
                    chapter = chapter,
                    work = work,
                    layout = layout,
                    direction = direction,
                    pages = pages,
                    initialPage = progress?.pageIndex?.coerceIn(0, (pages.size - 1).coerceAtLeast(0)) ?: 0,
                    initialScroll = progress?.scrollFraction ?: 0f,
                    prevChapterId = siblings.getOrNull(here - 1)?.id,
                    nextChapterId = siblings.getOrNull(here + 1)?.id,
                )
            } catch (e: Exception) {
                _state.value = ReaderState(loading = false, error = e.message ?: "Falha ao abrir")
            }
        }
    }

    /** Progresso do modo paginado: número de página; marca lido ao chegar na última. */
    fun savePagedProgress(pageIndex: Int) {
        val chapter = _state.value.chapter ?: return
        viewModelScope.launch {
            graph.library.saveProgress(
                ChapterProgress(
                    chapterId = chapterId,
                    pageIndex = pageIndex,
                    scrollFraction = 0f,
                    completed = chapter.pageCount > 0 && pageIndex >= chapter.pageCount - 1,
                    updatedAt = nowMillis(),
                ),
            )
        }
    }

    /** Progresso do long strip: fração de rolagem; marca lido perto do fim. */
    fun saveScrollProgress(fraction: Float) {
        viewModelScope.launch {
            graph.library.saveProgress(
                ChapterProgress(
                    chapterId = chapterId,
                    pageIndex = 0,
                    scrollFraction = fraction.coerceIn(0f, 1f),
                    completed = fraction >= 0.99f,
                    updatedAt = nowMillis(),
                ),
            )
        }
    }

    /** Override de layout do capítulo (task 5.3); `null` restaura a detecção. Recarrega. */
    fun setChapterLayoutOverride(layout: Layout?) {
        viewModelScope.launch {
            graph.library.setChapterLayoutOverride(chapterId, layout)
            load()
        }
    }

    /** Override de layout da obra (task 5.3); `null` restaura a detecção. Recarrega. */
    fun setWorkLayoutOverride(layout: Layout?) {
        val workUuid = _state.value.chapter?.workUuid ?: return
        viewModelScope.launch {
            graph.library.setWorkLayoutOverride(workUuid, layout)
            load()
        }
    }

    /** Direção da obra (task 5.3). Recarrega. */
    fun setDirection(direction: ReadingDirection) {
        val workUuid = _state.value.chapter?.workUuid ?: return
        viewModelScope.launch {
            graph.library.setWorkDirection(workUuid, direction)
            load()
        }
    }
}
