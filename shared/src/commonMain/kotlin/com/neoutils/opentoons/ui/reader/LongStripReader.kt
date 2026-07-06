package com.neoutils.opentoons.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.neoutils.opentoons.domain.model.ArchiveImage
import com.neoutils.opentoons.domain.model.Page
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Renderer long strip (task 6.2): `LazyColumn` vertical contínuo, sem virada nem zoom livre.
 * Cada página é `fillMaxWidth` e o Coil decodifica ao tamanho do viewport — downscale que
 * mantém a memória limitada (fallback anti-OOM do Risco nº 1, sem tiling — D7). Tap alterna
 * a chrome; o progresso é fração de rolagem (task 6.4).
 */
@Composable
fun LongStripReader(
    archivePath: String,
    pages: List<Page>,
    initialScroll: Float,
    onScrollFraction: (Float) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    val listState = rememberLazyListState()

    // Retoma a posição pela fração inicial (task 6.4 / offline-library).
    LaunchedEffect(Unit) {
        val index = (initialScroll * (pages.size - 1)).toInt().coerceIn(0, pages.lastIndex)
        if (index > 0) listState.scrollToItem(index)
    }

    // Persiste a fração quando a rolagem estabiliza.
    val fraction by remember {
        derivedStateOf {
            if (pages.size <= 1) 1f
            else listState.firstVisibleItemIndex.toFloat() / (pages.size - 1)
        }
    }
    LaunchedEffect(listState) {
        snapshotFractionFlow(listState).distinctUntilChanged().collect { scrolling ->
            if (!scrolling) onScrollFraction(fraction)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onToggleChrome() } },
    ) {
        items(pages, key = { it.entryName }) { page ->
            AsyncImage(
                model = ArchiveImage(archivePath, page.entryName),
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun snapshotFractionFlow(listState: androidx.compose.foundation.lazy.LazyListState) =
    androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
