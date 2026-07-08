package com.neoutils.opentoons.ui.importer

import androidx.compose.runtime.Composable

/** Android: mobile → bottom sheet (ciente de IME). */
@Composable
actual fun ImportModalShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) = BottomSheetShell(dismissable, onDismiss, content)
