package com.neoutils.opentoons

import com.neoutils.opentoons.domain.model.Layout
import com.neoutils.opentoons.domain.model.effectiveLayout
import com.neoutils.opentoons.util.LayoutHeuristic
import com.neoutils.opentoons.util.NaturalOrder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cobre cenários das specs verificáveis por lógica pura (task 8.1):
 * ordenação natural (content-import), detecção de layout e precedência de override
 * (reading-experience). Import copy-in, direção, progresso e offline dependem de
 * FileKit/Room/arquivos reais e ficam para o E2E manual (tasks 8.2–8.3).
 */
class ReaderLogicTest {

    // spec content-import — Scenario: Ordenação natural
    @Test
    fun naturalOrder_pag2_antesDe_pag10() {
        val input = listOf("pag10.jpg", "pag2.jpg", "pag1.jpg", "pag100.jpg")
        val sorted = input.sortedWith(NaturalOrder)
        assertEquals(listOf("pag1.jpg", "pag2.jpg", "pag10.jpg", "pag100.jpg"), sorted)
    }

    @Test
    fun naturalOrder_comZerosAEsquerda_eSubpastas() {
        val input = listOf("c1/p003.png", "c1/p02.png", "c1/p1.png", "c1/p10.png")
        val sorted = input.sortedWith(NaturalOrder)
        assertEquals(listOf("c1/p1.png", "c1/p02.png", "c1/p003.png", "c1/p10.png"), sorted)
    }

    // spec reading-experience — Scenario: Capítulo de tiras altas
    @Test
    fun heuristica_tirasAltas_ehLongStrip() {
        val ratios = listOf(3.5, 4.0, 5.0, 3.8) // altura/largura elevada
        assertEquals(Layout.LONG_STRIP, LayoutHeuristic.detect(ratios))
    }

    // spec reading-experience — Scenario: Capítulo de páginas normais
    @Test
    fun heuristica_paginasNormais_ehPaginado() {
        val ratios = listOf(1.4, 1.5, 1.42, 1.5) // retrato comum
        assertEquals(Layout.PAGED, LayoutHeuristic.detect(ratios))
    }

    @Test
    fun heuristica_medianaNoLimiar() {
        // mediana >= 2.0 → long strip (threshold a calibrar — task 8.4)
        assertEquals(Layout.LONG_STRIP, LayoutHeuristic.detect(listOf(1.9, 2.1, 2.0)))
        assertEquals(Layout.PAGED, LayoutHeuristic.detect(listOf(1.9, 1.95, 1.8)))
    }

    // spec reading-experience — Requirement: Override manual de layout com precedência
    @Test
    fun precedencia_capituloVenceObraVenceDetectado() {
        assertEquals(
            Layout.PAGED,
            effectiveLayout(chapterOverride = Layout.PAGED, workOverride = Layout.LONG_STRIP, detected = Layout.LONG_STRIP),
        )
        assertEquals(
            Layout.LONG_STRIP,
            effectiveLayout(chapterOverride = null, workOverride = Layout.LONG_STRIP, detected = Layout.PAGED),
        )
    }

    // spec reading-experience — Scenario: Limpar override restaura a detecção
    @Test
    fun precedencia_semOverride_usaDetectado() {
        assertEquals(
            Layout.LONG_STRIP,
            effectiveLayout(chapterOverride = null, workOverride = null, detected = Layout.LONG_STRIP),
        )
    }
}
