package com.neoutils.opentoons.ui.reader

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.event.MouseWheelEvent
import kotlin.math.abs

/**
 * `actual` desktop (tasks 7.3–7.4): intercepta a rolagem no *pass* inicial (antes da
 * `LazyColumn`) e, **só para roda discreta**, rola uma fração do viewport por notch via
 * [ScrollableState.dispatchRawDelta], consumindo o evento. Trackpad/gestos de precisão passam
 * intocados para o tratamento padrão (não são amplificados).
 *
 * **Discriminação (roda vs trackpad), calibrada pelo spike (task 7.1):** um `MouseWheelEvent`
 * de roda tem `preciseWheelRotation` **inteiro exato** (1.0, 2.0, …); o trackpad emite rajadas
 * de inércia com rotação fracionária (0.1, 0.94, 3.58, …) — inclusive a cauda logo abaixo de um
 * inteiro (−0.9998). O teste por **truncação** (`precise.toLong()`) separa os dois: `−0.9998`
 * trunca para `0` (rejeitado), `−1.0` para `−1` (aceito). Medido em 338 eventos reais de
 * trackpad: **zero falsos positivos**.
 *
 * **Amplificação (task 7.2):** o Compose Desktop entrega um delta constante em px por notch,
 * indiferente ao tamanho dos itens — atravessar um capítulo custa centenas de notches. Em vez
 * de multiplicar esse delta (cuja escala varia por SO), rola-se [NOTCH_VIEWPORT_FRACTION] da
 * **altura do viewport** por notch (×nº de notches num giro rápido). É proporcional ao viewport
 * (opção do design), independente da escala de px-por-linha da plataforma, e afeta apenas a roda.
 */
actual fun Modifier.wheelScrollBoost(state: ScrollableState): Modifier = this.pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val wheel = event.awtEventOrNull as? MouseWheelEvent ?: continue
            val notches = discreteNotchesOrZero(wheel)
            if (notches == 0) continue // trackpad/precisão → tratamento padrão
            state.dispatchRawDelta(notches * size.height * NOTCH_VIEWPORT_FRACTION)
            event.changes.forEach { it.consume() }
        }
    }
}

/**
 * Nº de notches (com sinal) se o evento for de **roda discreta**; `0` para trackpad/precisão.
 * Roda ⇒ `preciseWheelRotation` inteiro exato: a parte truncada coincide com a precisa.
 */
private fun discreteNotchesOrZero(e: MouseWheelEvent): Int {
    val precise = e.preciseWheelRotation
    val truncated = precise.toLong()
    return if (truncated != 0L && abs(precise - truncated.toDouble()) < INTEGER_EPS) {
        truncated.toInt()
    } else {
        0
    }
}

/** Fração da altura do viewport rolada por notch (≈4 notches por tela). */
private const val NOTCH_VIEWPORT_FRACTION = 0.25f

/** Tolerância para considerar `preciseWheelRotation` inteiro (roda) e não fracionário (trackpad). */
private const val INTEGER_EPS = 1e-3
