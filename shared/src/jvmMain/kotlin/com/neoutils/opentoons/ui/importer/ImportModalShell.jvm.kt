package com.neoutils.opentoons.ui.importer

import androidx.compose.runtime.Composable

/** Desktop: mantém o `Dialog` centralizado (bottom sheet no desktop é estranho). */
@Composable
actual fun ImportModalShell(
    dismissable: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) = DialogShell(dismissable, onDismiss, content)
