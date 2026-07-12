package com.neoutils.opentoons.ui.reader

import kotlin.math.max
import kotlin.math.roundToInt

/** Geometria nativa de uma página em px; `0` = desconhecida (formato fora do sniff). */
data class PageGeometry(val width: Int, val height: Int)

/**
 * Um tile é o item indivisível do long strip: uma faixa `[srcTop, srcTop+srcHeight)` da página
 * (no espaço da **fonte**, px nativos) renderizada com altura fixa [heightPx] (espaço de
 * **exibição**). `srcHeight == 0` sinaliza geometria desconhecida → decode de página inteira
 * (fallback, task 5.5).
 */
data class Tile(
    val pageIndex: Int,
    val tileIndex: Int,
    val srcTop: Int,
    val srcHeight: Int,
    val heightPx: Int,
)

/** Posição de leitura independente de layout (design D4, task 4.1). */
data class Position(val pageIndex: Int, val fractionWithinPage: Float)

/** Alvo de rolagem para a `LazyColumn`: índice do tile + deslocamento em px dentro dele. */
data class ScrollTarget(val tileIndex: Int, val offsetPx: Int)

/**
 * Layout puro do long strip (design D2, tasks 2.1–2.3): mapeia
 * `(geometria das páginas, larguraDeConteúdo, alturaDeTile) → List<Tile>` **sem dependência de
 * UI**. Cada tile tem altura **conhecida antes da composição** — nenhum item muda de altura
 * depois de medido (invariante I2), então o salto ao rolar para cima deixa de ser compensável
 * e passa a ser inexpressável (I1).
 *
 * As fronteiras de tile são inteiras tanto no espaço de exibição quanto no da fonte (via
 * arredondamento cumulativo), então somam exatamente a altura da página — sem tile residual de
 * poucos px (task 2.3) e sem costura entre tiles adjacentes (task 3.4). Páginas curtas (mangá
 * misturado) resultam num único tile.
 *
 * [positionOf]/[positionAt] são inversas e vivem no espaço `(pageIndex, fractionWithinPage)`,
 * estável entre larguras/DPIs/tilings (I3/I6). É o contrato testável sem UI.
 */
class LongStripLayout(
    pages: List<PageGeometry>,
    val contentWidthPx: Int,
    val tileHeightPx: Int = DEFAULT_TILE_HEIGHT_PX,
) {

    val tiles: List<Tile>

    /** Topo cumulativo (px de exibição) de cada página; `[n]` = altura total. */
    private val pageTops: LongArray

    /** Altura de exibição (px) de cada página = soma das alturas dos seus tiles. */
    private val pageHeights: IntArray

    /** Topo cumulativo (px de exibição) de cada tile; `[tiles.size]` = altura total. */
    private val tileTops: LongArray

    val pageCount: Int = pages.size

    init {
        val cw = contentWidthPx.coerceAtLeast(1)
        val th = tileHeightPx.coerceAtLeast(1)
        val allTiles = ArrayList<Tile>()
        pageTops = LongArray(pages.size + 1)
        pageHeights = IntArray(pages.size)

        var top = 0L
        pages.forEachIndexed { pageIndex, g ->
            val known = g.width > 0 && g.height > 0
            // Altura de exibição da página inteira, escalada para a largura de conteúdo.
            val displayHeight = if (known) {
                ((cw.toLong() * g.height) / g.width).toInt().coerceAtLeast(1)
            } else {
                (cw * FALLBACK_ASPECT).roundToInt().coerceAtLeast(1)
            }
            // Divide em partes iguais (~th px cada); página curta → 1 tile.
            val k = max(1, (displayHeight.toDouble() / th).roundToInt())

            var prevDisplay = 0
            var prevSrc = 0
            for (t in 0 until k) {
                val display = ((displayHeight.toLong() * (t + 1)) / k).toInt()
                val src = if (known) ((g.height.toLong() * (t + 1)) / k).toInt() else 0
                allTiles += Tile(
                    pageIndex = pageIndex,
                    tileIndex = t,
                    srcTop = prevSrc,
                    srcHeight = src - prevSrc,
                    heightPx = display - prevDisplay,
                )
                prevDisplay = display
                prevSrc = src
            }
            pageHeights[pageIndex] = displayHeight
            top += displayHeight
            pageTops[pageIndex + 1] = top
        }

        tiles = allTiles
        tileTops = LongArray(allTiles.size + 1)
        var acc = 0L
        allTiles.forEachIndexed { i, tile ->
            acc += tile.heightPx
            tileTops[i + 1] = acc
        }
    }

    /** Altura total do capítulo (px de exibição), conhecida no open (I3). */
    val totalHeightPx: Long get() = pageTops[pageCount]

    /** Converte `(página, fração)` em posição absoluta em px (inversa de [positionAt]). */
    fun positionOf(pageIndex: Int, fractionWithinPage: Float): Long {
        if (pageCount == 0) return 0L
        val p = pageIndex.coerceIn(0, pageCount - 1)
        val f = fractionWithinPage.coerceIn(0f, 1f)
        return pageTops[p] + (f * pageHeights[p]).toLong()
    }

    /** Converte uma posição absoluta em px na `(página, fração)` que a contém (inversa de [positionOf]). */
    fun positionAt(px: Long): Position {
        if (pageCount == 0) return Position(0, 0f)
        val clamped = px.coerceIn(0L, (totalHeightPx - 1).coerceAtLeast(0L))
        val p = pageIndexAt(clamped)
        val h = pageHeights[p]
        val f = if (h <= 0) 0f else ((clamped - pageTops[p]).toFloat() / h).coerceIn(0f, 1f)
        return Position(p, f)
    }

    /** Posição de leitura a partir do estado da `LazyColumn` (tile visível + offset). */
    fun positionAtTile(tileIndex: Int, offsetPx: Int): Position {
        if (tiles.isEmpty()) return Position(0, 0f)
        val t = tileIndex.coerceIn(0, tiles.lastIndex)
        return positionAt(tileTops[t] + offsetPx)
    }

    /** Alvo de rolagem (tile + offset) para uma posição absoluta em px. */
    fun scrollTargetFor(px: Long): ScrollTarget {
        if (tiles.isEmpty()) return ScrollTarget(0, 0)
        val clamped = px.coerceIn(0L, (totalHeightPx - 1).coerceAtLeast(0L))
        val t = tileIndexAt(clamped)
        return ScrollTarget(t, (clamped - tileTops[t]).toInt())
    }

    /** Alvo de rolagem para uma `(página, fração)`. */
    fun scrollTargetFor(pageIndex: Int, fractionWithinPage: Float): ScrollTarget =
        scrollTargetFor(positionOf(pageIndex, fractionWithinPage))

    private fun pageIndexAt(px: Long): Int = upperBound(pageTops, px, pageCount)

    private fun tileIndexAt(px: Long): Int = upperBound(tileTops, px, tiles.size)

    // Maior i tal que tops[i] <= px, no domínio [0, count-1].
    private fun upperBound(tops: LongArray, px: Long, count: Int): Int {
        var lo = 0
        var hi = count - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (tops[mid] <= px) lo = mid else hi = mid - 1
        }
        return lo
    }

    companion object {
        /** Alvo de altura de tile (px). Chute informado pelo limite de textura; a fixar por perfil (task 5.10). */
        const val DEFAULT_TILE_HEIGHT_PX = 2048

        /** Proporção (altura/largura) assumida para páginas sem geometria conhecida. */
        const val FALLBACK_ASPECT = 1.4f

        /** Breakpoint de classe de janela (dp): abaixo preenche a largura, acima limita a coluna. */
        const val COMPACT_BREAKPOINT_DP = 600f

        /** Largura máxima da coluna de conteúdo (dp) em janelas média/expandida. */
        const val MAX_CONTENT_DP = 700f

        /**
         * Largura de conteúdo (px) por **classe de janela** (design D3, task 2.7): janela
         * compacta (`< 600dp`) preenche a largura; janela média/expandida (`≥ 600dp`) limita a
         * coluna a ~700dp. O breakpoint é a largura da janela, não o tipo de dispositivo — um
         * celular em paisagem cai no ramo da coluna centralizada.
         */
        fun contentWidthPx(windowWidthDp: Float, density: Float): Int {
            val dp = if (windowWidthDp < COMPACT_BREAKPOINT_DP) {
                windowWidthDp
            } else {
                minOf(windowWidthDp, MAX_CONTENT_DP)
            }
            return (dp * density).roundToInt().coerceAtLeast(1)
        }
    }
}
