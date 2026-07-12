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
 * `LazyColumn`), e **só para roda discreta** aplica um delta amplificado via
 * [ScrollableState.dispatchRawDelta] e consome o evento. Trackpad/gestos de precisão (delta
 * fracionário de alta frequência) passam intocados para o tratamento padrão.
 *
 * A discriminação usa o evento AWT: `MouseWheelEvent` com `scrollType == WHEEL_UNIT_SCROLL` e
 * `preciseWheelRotation` **inteiro** (≈ `wheelRotation`) é uma roda; rotação fracionária é
 * trackpad. [WHEEL_STEP_PX] e [AMPLIFY] são provisórios — o valor definitivo sai do spike
 * (task 7.2).
 */
actual fun Modifier.wheelScrollBoost(state: ScrollableState): Modifier = this.pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val wheel = event.awtEventOrNull as? MouseWheelEvent ?: continue
            if (!isDiscreteWheel(wheel)) continue
            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: continue
            if (delta == 0f) continue
            state.dispatchRawDelta(delta * WHEEL_STEP_PX * AMPLIFY)
            event.changes.forEach { it.consume() }
        }
    }
}

// Roda discreta: rotação de precisão praticamente inteira. Trackpad emite frações.
private fun isDiscreteWheel(e: MouseWheelEvent): Boolean {
    val precise = e.preciseWheelRotation
    return abs(precise - precise.toLong().toDouble()) < 1e-3 && precise != 0.0
}

/** Passo em px por unidade de delta de roda (provisório — a fixar pelo spike, task 7.2). */
private const val WHEEL_STEP_PX = 64f

/** Fator de amplificação da roda discreta (provisório — a fixar pelo spike, task 7.2). */
private const val AMPLIFY = 3f
