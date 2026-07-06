package com.neoutils.opentoons.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.neoutils.opentoons.domain.model.ArchiveImage
import com.neoutils.opentoons.domain.model.Page
import com.neoutils.opentoons.domain.model.ReadingDirection
import kotlinx.coroutines.launch

/**
 * Renderer paginado (task 6.1): `HorizontalPager` com avanço discreto e direção RTL/LTR
 * (`reverseLayout`). Zoom manual por página via `graphicsLayer` + `detectTransformGestures`
 * (pinch/pan) e double-tap — fallback do Risco nº 1 (sem Telephoto/tiling; ver D7). Tap nas
 * zonas laterais avança/volta respeitando a direção; tap central alterna a chrome (6.3).
 */
@Composable
fun PagedReader(
    archivePath: String,
    pages: List<Page>,
    direction: ReadingDirection,
    initialPage: Int,
    onPageSettled: (Int) -> Unit,
    onToggleChrome: () -> Unit,
    seekToPage: Int?,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialPage.coerceIn(0, pages.lastIndex)) { pages.size }
    val scope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // Zera o zoom ao trocar de página.
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offset = Offset.Zero
    }

    // Persiste o progresso quando a página se assenta.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect(onPageSettled)
    }

    // Seek externo (slider da chrome).
    LaunchedEffect(seekToPage) {
        seekToPage?.let { if (it != pagerState.currentPage) pagerState.animateScrollToPage(it) }
    }

    fun advance(delta: Int) {
        val target = (pagerState.currentPage + delta).coerceIn(0, pages.lastIndex)
        if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    HorizontalPager(
        state = pagerState,
        reverseLayout = direction == ReadingDirection.RTL,
        userScrollEnabled = scale == 1f, // quando com zoom, o gesto vira pan
        modifier = modifier.fillMaxSize(),
    ) { index ->
        AsyncImage(
            model = ArchiveImage(archivePath, pages[index].entryName),
            contentDescription = "Página ${index + 1}",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(index, direction) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = { pos ->
                            if (scale > 1f) {
                                onToggleChrome()
                                return@detectTapGestures
                            }
                            val w = size.width
                            val leftZone = pos.x < w * 0.3f
                            val rightZone = pos.x > w * 0.7f
                            // LTR: direita avança; RTL: espelha.
                            val forward = if (direction == ReadingDirection.RTL) leftZone else rightZone
                            val backward = if (direction == ReadingDirection.RTL) rightZone else leftZone
                            when {
                                forward -> advance(+1)
                                backward -> advance(-1)
                                else -> onToggleChrome()
                            }
                        },
                    )
                }
                .then(
                    // Só captura pan/zoom quando já ampliado (double-tap p/ ampliar). Em escala
                    // 1, este detector consumiria o arrasto horizontal e o swipe de página do
                    // pager não funcionaria — era o bug do "swipe não passa página".
                    if (scale > 1f) {
                        Modifier.pointerInput(index) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val next = (scale * zoom).coerceIn(1f, 5f)
                                scale = next
                                offset = if (next > 1f) offset + pan else Offset.Zero
                            }
                        }
                    } else {
                        Modifier
                    },
                )
                .graphicsLayer {
                    if (index == pagerState.currentPage) {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                },
        )
    }
}
