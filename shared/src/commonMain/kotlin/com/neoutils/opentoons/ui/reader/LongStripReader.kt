package com.neoutils.opentoons.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.neoutils.opentoons.data.image.TileCache
import com.neoutils.opentoons.data.image.TileLoader
import com.neoutils.opentoons.domain.model.Page
import kotlinx.coroutines.launch

/**
 * Renderer long strip sobre **tiles de altura fixa** (design D2, tasks 3.1/3.4). Cada item da
 * `LazyColumn` é um [Tile] com `Modifier.height(tile.heightPx)` **conhecido antes da
 * composição** — a altura nunca depende de nada assíncrono, então nenhum item muda de altura
 * depois de medido (invariante I2). O salto ao rolar para cima — que era o colapso de itens
 * reentrantes de altura outrora dependente do decode — deixa de ser compensável e passa a ser
 * inexpressável.
 *
 * A geometria vem de [Page] (sniff no open, task 1.6); o [LongStripLayout] a converte em tiles.
 * Cada tile é decodificado **por região** ([TileLoader]/[decodeRegion]) na largura de conteúdo,
 * sem upscale, com cache LRU por orçamento ([TileCache]) e prefetch direcional. O progresso é
 * `(pageIndex, fractionWithinPage)` (design D4), estável a rotação e troca de largura.
 */
@Composable
fun LongStripReader(
    archivePath: String,
    pages: List<Page>,
    initialPage: Int,
    initialFraction: Float,
    seekTarget: Int?,
    onProgress: (position: Position, atEnd: Boolean) -> Unit,
    onToggleChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return

    val density = LocalDensity.current.density
    val cache = remember { TileCache(TileCache.DEFAULT_BUDGET_BYTES) }
    val loader = remember { TileLoader() }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val windowWidthDp = maxWidth.value
        val viewportPx = with(LocalDensity.current) { maxHeight.toPx() }.toLong()
        val contentWidthPx = LongStripLayout.contentWidthPx(windowWidthDp, density)
        val contentWidthDp = with(LocalDensity.current) { contentWidthPx.toDp() }

        val geometries = remember(pages) { pages.map { PageGeometry(it.width, it.height) } }
        val layout = remember(geometries, contentWidthPx) {
            LongStripLayout(geometries, contentWidthPx)
        }

        val listState = rememberLazyListState()

        // Posição corrente em espaço lógico — sobrevive à troca de layout (rotação/largura).
        val position = remember { mutableStateOf(Position(initialPage, initialFraction)) }

        // Restaura a posição sempre que o layout muda (primeiro open e rotação): a mesma
        // (página, fração) reconstrói o px na nova largura (task 4.5/4.6/2.7).
        LaunchedEffect(layout) {
            val target = layout.scrollTargetFor(
                position.value.pageIndex,
                position.value.fractionWithinPage,
            )
            listState.scrollToItem(target.tileIndex, target.offsetPx)
        }

        // Acompanha a rolagem: atualiza a posição lógica e, quando estabiliza, persiste/atualiza
        // o rodapé. `atEnd` corrige o `completed` (task 4.4): a base do viewport perto do fim,
        // alcançável mesmo com a última página mais curta que a tela.
        LaunchedEffect(layout, listState) {
            snapshotFlow {
                Triple(
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    listState.isScrollInProgress,
                )
            }.collect { (index, offset, scrolling) ->
                val pos = layout.positionAtTile(index, offset)
                position.value = pos
                if (!scrolling) {
                    val scrollPx = layout.positionOf(pos.pageIndex, pos.fractionWithinPage)
                    val total = layout.totalHeightPx
                    val atEnd = total > 0 && scrollPx + viewportPx >= 0.99 * total
                    onProgress(pos, atEnd)
                }
            }
        }

        // Seek externo (setas/PageUp-Down e slider da chrome — tasks 6.1/6.3): posiciona a
        // rolagem exatamente no topo da página-alvo.
        LaunchedEffect(seekTarget) {
            seekTarget?.let {
                val target = layout.scrollTargetFor(it, 0f)
                listState.animateScrollToItem(target.tileIndex, target.offsetPx)
            }
        }

        // Prefetch direcional (task 5.8): a direção da rolagem decide qual lado pré-carregar.
        LaunchedEffect(layout, listState) {
            var last = listState.firstVisibleItemIndex
            snapshotFlow { listState.firstVisibleItemIndex }.collect { idx ->
                val forward = idx >= last
                last = idx
                val range = if (forward) (idx + 1)..(idx + PREFETCH) else (idx - PREFETCH) until idx
                for (i in range) {
                    val tile = layout.tiles.getOrNull(i) ?: continue
                    launch {
                        loader.load(
                            archivePath,
                            pages[tile.pageIndex].entryName,
                            tile,
                            contentWidthPx,
                            cache,
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                // Roda de mouse discreta no desktop (task 7.3); no-op no mobile.
                .wheelScrollBoost(listState)
                .pointerInput(Unit) { detectTapGestures { onToggleChrome() } },
        ) {
            items(
                items = layout.tiles,
                key = { "${it.pageIndex}:${it.tileIndex}" },
            ) { tile ->
                TileView(
                    archivePath = archivePath,
                    entryName = pages[tile.pageIndex].entryName,
                    tile = tile,
                    targetWidthPx = contentWidthPx,
                    widthDp = contentWidthDp,
                    heightDp = with(LocalDensity.current) { tile.heightPx.toDp() },
                    cache = cache,
                    loader = loader,
                )
            }
        }
    }
}

/**
 * Um tile: a altura ([heightDp]) é **fixa** e independe do decode — a caixa mede o mesmo com ou
 * sem bitmap carregado (I2). O bitmap chega assíncrono e preenche a caixa; até lá, fundo preto.
 */
@Composable
private fun TileView(
    archivePath: String,
    entryName: String,
    tile: Tile,
    targetWidthPx: Int,
    widthDp: androidx.compose.ui.unit.Dp,
    heightDp: androidx.compose.ui.unit.Dp,
    cache: TileCache,
    loader: TileLoader,
) {
    val key = TileLoader.key(archivePath, entryName, tile, targetWidthPx)
    var bitmap by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        bitmap = loader.load(archivePath, entryName, tile, targetWidthPx, cache)
    }
    Box(
        Modifier
            .width(widthDp)
            .height(heightDp)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                // A caixa já tem a proporção do tile; FillBounds mapeia exato, sem costura.
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Quantos tiles pré-carregar à frente na direção da rolagem. */
private const val PREFETCH = 4
