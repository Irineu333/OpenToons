package com.neoutils.opentoons

import com.neoutils.opentoons.ui.reader.LongStripLayout
import com.neoutils.opentoons.ui.reader.PageGeometry
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Suíte de propriedades do [LongStripLayout] sem UI (task 8.1), cobrindo as invariantes do
 * design: I1 (altura é função pura de `(geometria, larguraDeConteúdo)`), I3 (`totalHeightPx` é
 * a soma das alturas dos tiles; `positionOf`/`positionAt` inversas) e I6 (o defeito relatado
 * como propriedade: rolar `+N`/`−N` devolve à posição). As geometrias são pseudoaleatórias
 * (semente fixa) para varrer o domínio de forma reprodutível.
 */
class LongStripLayoutTest {

    private fun randomGeometries(rnd: Random, n: Int): List<PageGeometry> =
        List(n) {
            // Mistura páginas de mangá (baixas) com tiras altas de webtoon.
            val w = rnd.nextInt(400, 2000)
            val h = rnd.nextInt(200, 20_000)
            PageGeometry(w, h)
        }

    // ---- I1: pureza ----

    @Test
    fun i1_alturaEhFuncaoPuraDosInputs() {
        val rnd = Random(42)
        val pages = randomGeometries(rnd, 40)
        val a = LongStripLayout(pages, contentWidthPx = 1080)
        val b = LongStripLayout(pages, contentWidthPx = 1080)
        // Mesma entrada → tiles idênticos, sem estado.
        assertEquals(a.tiles, b.tiles)
        assertEquals(a.totalHeightPx, b.totalHeightPx)
    }

    // ---- I3: total conhecido e soma dos tiles ----

    @Test
    fun i3_totalEhSomaDasAlturasDosTiles() {
        val pages = randomGeometries(Random(7), 60)
        val layout = LongStripLayout(pages, contentWidthPx = 900)
        val soma = layout.tiles.sumOf { it.heightPx.toLong() }
        assertEquals(layout.totalHeightPx, soma)
    }

    @Test
    fun i3_positionOf_ePositionAt_saoInversas() {
        val pages = randomGeometries(Random(11), 30)
        val layout = LongStripLayout(pages, contentWidthPx = 1080)
        for (p in pages.indices) {
            for (f in listOf(0f, 0.25f, 0.5f, 0.75f, 0.999f)) {
                val px = layout.positionOf(p, f)
                val pos = layout.positionAt(px)
                assertEquals(p, pos.pageIndex, "página deve reconstruir em p=$p f=$f")
                // Erro de fração limitado pelo arredondamento de 1 px na altura da página.
                val pageHeight = layout.positionOf(p, 1f) - layout.positionOf(p, 0f)
                val tol = if (pageHeight <= 0) 1f else (2f / pageHeight)
                assertTrue(
                    kotlin.math.abs(pos.fractionWithinPage - f) <= tol + 1e-4f,
                    "fração p=$p f=$f → ${pos.fractionWithinPage} (tol=$tol)",
                )
            }
        }
    }

    // ---- I6: rolar +N e −N devolve à posição (o defeito relatado) ----

    @Test
    fun i6_rolarIdaEVoltaDevolveAPosicao() {
        val pages = randomGeometries(Random(99), 50)
        val layout = LongStripLayout(pages, contentWidthPx = 1080)
        val total = layout.totalHeightPx
        val rnd = Random(1234)
        repeat(500) {
            val px = (rnd.nextDouble() * total).toLong().coerceIn(0, total - 1)
            val pos = layout.positionAt(px)
            // Reconstruir px a partir de (página, fração) e reler devolve a MESMA página/fração.
            val px2 = layout.positionOf(pos.pageIndex, pos.fractionWithinPage)
            val pos2 = layout.positionAt(px2)
            assertEquals(pos.pageIndex, pos2.pageIndex)
            assertTrue(kotlin.math.abs(pos.fractionWithinPage - pos2.fractionWithinPage) <= 1e-3f)
        }
    }

    @Test
    fun i6_scrollTarget_ePositionAtTile_saoConsistentes() {
        val pages = randomGeometries(Random(5), 25)
        val layout = LongStripLayout(pages, contentWidthPx = 720)
        val total = layout.totalHeightPx
        val rnd = Random(2024)
        repeat(300) {
            val px = (rnd.nextDouble() * total).toLong().coerceIn(0, total - 1)
            val target = layout.scrollTargetFor(px)
            val pos = layout.positionAtTile(target.tileIndex, target.offsetPx)
            val direct = layout.positionAt(px)
            assertEquals(direct.pageIndex, pos.pageIndex)
            assertTrue(kotlin.math.abs(direct.fractionWithinPage - pos.fractionWithinPage) <= 1e-3f)
        }
    }

    // ---- 2.3: divisão em partes iguais, sem residual minúsculo ----

    @Test
    fun tiles_divididosEmPartesQuaseIguais_semResidualMinusculo() {
        val pages = randomGeometries(Random(3), 40)
        val layout = LongStripLayout(pages, contentWidthPx = 1080)
        for (p in pages.indices) {
            val tilesDaPagina = layout.tiles.filter { it.pageIndex == p }
            assertTrue(tilesDaPagina.isNotEmpty())
            val alturas = tilesDaPagina.map { it.heightPx }
            // Partes quase iguais: diferem no máximo 1 px (arredondamento cumulativo).
            assertTrue(alturas.max() - alturas.min() <= 1, "página $p: $alturas")
            // Fronteiras da fonte contíguas e somando a altura nativa (sem costura).
            val g = pages[p]
            assertEquals(0, tilesDaPagina.first().srcTop)
            assertEquals(g.height, tilesDaPagina.sumOf { it.srcHeight })
        }
    }

    @Test
    fun paginaCurta_resultaEmUnicoTile() {
        val curta = listOf(PageGeometry(1000, 400)) // ~432px de exibição a 1080 << 2048
        val layout = LongStripLayout(curta, contentWidthPx = 1080)
        assertEquals(1, layout.tiles.size)
    }

    @Test
    fun geometriaDesconhecida_usaFallback_eUmTile() {
        val layout = LongStripLayout(listOf(PageGeometry(0, 0)), contentWidthPx = 1000)
        assertEquals(1, layout.tiles.size)
        assertEquals(0, layout.tiles.first().srcHeight) // sinaliza decode de página inteira
        assertTrue(layout.totalHeightPx > 0)
    }

    // ---- 2.7: mudança de classe de janela preserva (página, fração) ----

    @Test
    fun mudancaDeLarguraPreservaPosicaoLogica() {
        val pages = randomGeometries(Random(77), 30)
        val compact = LongStripLayout(pages, LongStripLayout.contentWidthPx(500f, 2f))
        val expanded = LongStripLayout(pages, LongStripLayout.contentWidthPx(1200f, 2f))
        for (p in pages.indices) {
            for (f in listOf(0f, 0.33f, 0.66f, 0.9f)) {
                val a = compact.positionAt(compact.positionOf(p, f))
                val b = expanded.positionAt(expanded.positionOf(p, f))
                // A posição lógica (página, fração) é a mesma nas duas larguras.
                assertEquals(a.pageIndex, b.pageIndex)
                assertTrue(kotlin.math.abs(a.fractionWithinPage - b.fractionWithinPage) <= 5e-3f)
            }
        }
    }

    @Test
    fun contentWidth_respeitaBreakpointDeClasse() {
        // Compacta (< 600dp): preenche a largura.
        assertEquals(1000, LongStripLayout.contentWidthPx(500f, 2f)) // 500 * 2
        // Expandida (>= 600dp): limita a 700dp.
        assertEquals(1400, LongStripLayout.contentWidthPx(1200f, 2f)) // 700 * 2
        // Exatamente no breakpoint: já limita.
        assertEquals(1200, LongStripLayout.contentWidthPx(600f, 2f)) // 600 * 2
    }
}
