package com.neoutils.opentoons.util

import com.neoutils.opentoons.domain.model.Layout

/**
 * Heurística de detecção de layout por aspect-ratio (design D6, spec reading-experience,
 * task 5.1).
 *
 * `sinal = mediana(altura/largura)` das páginas amostradas. Mediana ≥ [STRIP_THRESHOLD]
 * indica tiras altas → long strip; abaixo disso, paginado. Um **pico** (mediana muito
 * alta, ≥ [STRIP_PEAK]) reforça strip mesmo com amostra pequena. Threshold a calibrar com
 * corpus real (task 8.4) — exposto como constante para facilitar o ajuste.
 */
object LayoutHeuristic {
    const val STRIP_THRESHOLD = 2.0
    const val STRIP_PEAK = 3.0

    /** Deriva o layout a partir das razões altura/largura das páginas. */
    fun detect(aspectRatios: List<Double>): Layout {
        if (aspectRatios.isEmpty()) return Layout.PAGED
        val m = median(aspectRatios)
        return if (m >= STRIP_THRESHOLD || m >= STRIP_PEAK) Layout.LONG_STRIP else Layout.PAGED
    }

    /** Deriva o layout a partir de tamanhos de imagem, descartando os inválidos. */
    fun detectFromSizes(sizes: List<ImageSize>): Layout = detect(
        sizes.filter { it.width > 0 }.map { it.height.toDouble() / it.width.toDouble() },
    )

    internal fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) sorted[n / 2]
        else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }
}
