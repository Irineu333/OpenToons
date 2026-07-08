package com.neoutils.opentoons.ui.importer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Shell do modal de import (improve-import): **`ModalBottomSheet` no mobile** (Android/iOS, ciente
 * de IME de fábrica) e **`Dialog` no desktop** — escolhido por `expect/actual`. Todo o miolo
 * (`ImportContent`) vive em `commonMain`; os `actual` só delegam a um dos dois shells abaixo.
 *
 * [dismissable] espelha o estado: `false` durante o processamento (não cancelar a meio de um save).
 */
@Composable
expect fun ImportModalShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
)

/**
 * Shell mobile: bottom sheet do Material3. Sobe acima do teclado e limita a altura à tela por
 * conta própria; quando não [dismissable], bloqueia o dismiss (scrim/drag/back) durante o save.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomSheetShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val currentDismissable by rememberUpdatedState(dismissable)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { target -> currentDismissable || target != SheetValue.Hidden },
    )
    ModalBottomSheet(
        onDismissRequest = { if (currentDismissable) onDismiss() },
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        content()
    }
}

/** Shell desktop: `Dialog` centralizado (comportamento anterior), dispensável conforme o estado. */
@Composable
fun DialogShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissable,
            dismissOnClickOutside = dismissable,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            content()
        }
    }
}
