package com.neoutils.opentoons.ui.reader

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

/**
 * Amplia a rolagem de **roda de mouse discreta** no desktop (design D6, task 7.3). O Compose
 * Desktop entrega um delta constante em dp por notch, indiferente ao tamanho dos itens —
 * atravessar um capítulo custa centenas de notches. O `actual` do desktop intercepta
 * `PointerEventType.Scroll`, aplica `state.dispatchRawDelta(delta × fator)` e consome o evento;
 * **trackpad e gestos de precisão não são amplificados** (task 7.4), distinguidos pelo evento
 * AWT. Em Android/iOS é no-op — a rolagem por gesto já escala com o fling (task 7.5).
 *
 * O fator é provisório: o valor definitivo (fixo, proporcional ao viewport ou configurável) sai
 * do spike de medição (tasks 7.1/7.2), ainda pendente por exigir execução no desktop real.
 */
expect fun Modifier.wheelScrollBoost(state: ScrollableState): Modifier
