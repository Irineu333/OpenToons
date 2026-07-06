package com.neoutils.opentoons.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.ReadingDirection
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onNavigateChapter: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            state.error != null -> Text(
                text = state.error ?: "",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                textAlign = TextAlign.Center,
            )
            state.pages.isEmpty() -> Text(
                "Capítulo sem páginas legíveis.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            else -> ReaderContent(state, viewModel, onBack, onNavigateChapter)
        }
    }
}

@Composable
private fun ReaderContent(
    state: ReaderState,
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
    onNavigateChapter: (String) -> Unit,
) {
    var chromeVisible by remember { mutableStateOf(false) }
    var currentPage by remember { mutableIntStateOf(state.initialPage) }
    var seekTarget by remember { mutableStateOf<Int?>(null) }
    var scrollPct by remember { mutableStateOf(state.initialScroll) }
    var showSettings by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val lastIndex = state.pages.lastIndex

    // Foco para receber teclas no desktop (task 6.6). No-op/benigno no mobile.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            // Camada de input de teclado (desktop) — task 6.6.
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight, Key.PageDown -> {
                        seekTarget = (currentPage + 1).coerceIn(0, lastIndex); true
                    }
                    Key.DirectionLeft, Key.PageUp -> {
                        seekTarget = (currentPage - 1).coerceIn(0, lastIndex); true
                    }
                    Key.Spacebar -> { chromeVisible = !chromeVisible; true }
                    else -> false
                }
            },
    ) {
        when (state.layout) {
            Layout.PAGED -> PagedReader(
                archivePath = state.chapter!!.archivePath,
                pages = state.pages,
                direction = state.direction,
                initialPage = state.initialPage,
                onPageSettled = {
                    currentPage = it
                    viewModel.savePagedProgress(it)
                },
                onToggleChrome = { chromeVisible = !chromeVisible },
                seekToPage = seekTarget,
            )
            Layout.LONG_STRIP -> LongStripReader(
                archivePath = state.chapter!!.archivePath,
                pages = state.pages,
                initialScroll = state.initialScroll,
                onScrollFraction = {
                    scrollPct = it
                    viewModel.saveScrollProgress(it)
                },
                onToggleChrome = { chromeVisible = !chromeVisible },
            )
        }

        // Chrome imersiva única (task 6.3): barras topo/base sobre toggle.
        AnimatedVisibility(chromeVisible, modifier = Modifier.align(Alignment.TopCenter)) {
            ReaderTopBar(
                title = state.chapter?.title ?: "",
                onBack = onBack,
                onSettings = { showSettings = true },
            )
        }
        AnimatedVisibility(chromeVisible, modifier = Modifier.align(Alignment.BottomCenter)) {
            ReaderBottomBar(
                layout = state.layout,
                currentPage = currentPage,
                pageCount = state.pages.size,
                scrollFraction = scrollPct,
                onSeek = { seekTarget = it },
                prevChapterId = state.prevChapterId,
                nextChapterId = state.nextChapterId,
                onNavigateChapter = onNavigateChapter,
            )
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onChapterLayout = viewModel::setChapterLayoutOverride,
            onWorkLayout = viewModel::setWorkLayoutOverride,
            onDirection = viewModel::setDirection,
        )
    }
}

@Composable
private fun ReaderTopBar(title: String, onBack: () -> Unit, onSettings: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().safeContentPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Voltar") }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = onSettings) { Text("Ajustes") }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    layout: Layout,
    currentPage: Int,
    pageCount: Int,
    scrollFraction: Float,
    onSeek: (Int) -> Unit,
    prevChapterId: String?,
    nextChapterId: String?,
    onNavigateChapter: (String) -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier.fillMaxWidth().safeContentPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            // Progresso por modo (task 6.4): página no paginado, % no long strip.
            val progressText = if (layout == Layout.PAGED) {
                "${currentPage + 1}/$pageCount"
            } else {
                "${(scrollFraction * 100).roundToInt()}%"
            }
            Text(progressText, style = MaterialTheme.typography.labelMedium)

            if (layout == Layout.PAGED && pageCount > 1) {
                Slider(
                    value = currentPage.toFloat(),
                    onValueChange = { onSeek(it.roundToInt().coerceIn(0, pageCount - 1)) },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = { prevChapterId?.let(onNavigateChapter) },
                    enabled = prevChapterId != null,
                ) { Text("‹ Capítulo") }
                TextButton(
                    onClick = { nextChapterId?.let(onNavigateChapter) },
                    enabled = nextChapterId != null,
                ) { Text("Capítulo ›") }
            }
        }
    }
}

/** Override de layout (obra/capítulo) e direção (obra) — task 5.3. "Auto" = limpar override. */
@Composable
private fun ReaderSettingsDialog(
    state: ReaderState,
    onDismiss: () -> Unit,
    onChapterLayout: (Layout?) -> Unit,
    onWorkLayout: (Layout?) -> Unit,
    onDirection: (ReadingDirection) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
        title = { Text("Ajustes de leitura") },
        text = {
            Column {
                Text("Layout do capítulo", style = MaterialTheme.typography.labelLarge)
                LayoutChips(
                    selected = state.chapter?.layoutOverride,
                    detected = state.chapter?.detectedLayout,
                    onSelect = onChapterLayout,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Layout da obra", style = MaterialTheme.typography.labelLarge)
                LayoutChips(
                    selected = state.work?.layoutOverride,
                    detected = null,
                    onSelect = onWorkLayout,
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("Direção (obra)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.direction == ReadingDirection.LTR,
                        onClick = { onDirection(ReadingDirection.LTR) },
                        label = { Text("LTR") },
                    )
                    FilterChip(
                        selected = state.direction == ReadingDirection.RTL,
                        onClick = { onDirection(ReadingDirection.RTL) },
                        label = { Text("RTL") },
                    )
                }
            }
        },
    )
}

@Composable
private fun LayoutChips(selected: Layout?, detected: Layout?, onSelect: (Layout?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val autoLabel = detected?.let { "Auto (${it.label()})" } ?: "Auto"
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text(autoLabel) },
        )
        FilterChip(
            selected = selected == Layout.PAGED,
            onClick = { onSelect(Layout.PAGED) },
            label = { Text("Paginado") },
        )
        FilterChip(
            selected = selected == Layout.LONG_STRIP,
            onClick = { onSelect(Layout.LONG_STRIP) },
            label = { Text("Long strip") },
        )
    }
}

private fun Layout.label(): String = when (this) {
    Layout.PAGED -> "Paginado"
    Layout.LONG_STRIP -> "Long strip"
}
