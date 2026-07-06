package com.neoutils.opentoons.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Tema Material 3 com esquema claro/escuro (task 1.4). O `MaterialTheme` não pinta fundo por
// si só — envolvemos o conteúdo num `Surface` de `background` para que TODAS as telas
// (biblioteca, detalhe) sigam o mesmo tema. O leitor usa fundo preto próprio (imersivo).
private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
