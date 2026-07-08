package com.neoutils.opentoons.ui.importer

import androidx.compose.runtime.Composable

/** iOS: mobile → bottom sheet. Tocar fora do campo (no miolo) é a única forma de baixar o teclado. */
@Composable
actual fun ImportModalShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) = BottomSheetShell(dismissable, onDismiss, content)
