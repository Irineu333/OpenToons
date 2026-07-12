package com.neoutils.opentoons.ui.reader

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier

// Mobile: a rolagem por gesto já escala com o fling (task 7.5) — sem amplificação.
actual fun Modifier.wheelScrollBoost(state: ScrollableState): Modifier = this
