package com.neoutils.opentoons.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neoutils.opentoons.domain.model.Work
import com.neoutils.opentoons.ui.icons.AppIcons
import com.neoutils.opentoons.util.ImportFormats
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/**
 * Biblioteca em grid de capas (tela inicial — spec offline-library, tasks 7.1/7.2). Importa
 * via FileKit filtrado por `cbz/cbr/zip/rar` (task 4.4). Favoritar por tap na estrela.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenWork: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val picker = rememberFilePickerLauncher(
        // Grade 2×2 (D3, task 4.4): unidade (cbz/cbr) e pacote (zip/rar). CBR/RAR só onde a
        // plataforma descompacta RAR — no iOS ficam de fora (D5). Tudo é normalizado para OPZ.
        type = FileKitType.File(ImportFormats.library),
    ) { file ->
        file?.let(viewModel::import)
    }

    Scaffold(
        floatingActionButton = {
            // Oculto durante o loading (inclui o import).
            if (uiState !is LibraryUiState.Loading) {
                ExtendedFloatingActionButton(
                    onClick = { picker.launch() },
                    text = { Text("Importar") },
                    icon = { Text("+", style = MaterialTheme.typography.titleLarge) },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Text(
                "Biblioteca",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            when (val state = uiState) {
                is LibraryUiState.Loading -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        state.message?.let { message ->
                            Spacer(Modifier.height(12.dp))
                            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                LibraryUiState.Empty -> CenteredBox {
                    Text(
                        "Nenhuma obra ainda.\nToque em Importar para adicionar um ${ImportFormats.libraryLabel}.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                is LibraryUiState.Error -> CenteredBox {
                    Text(
                        state.message,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                is LibraryUiState.Content -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.works, key = { it.id.uuid }) { work ->
                        WorkCover(
                            work = work,
                            onClick = { onOpenWork(work.id.uuid) },
                            onToggleFavorite = { viewModel.toggleFavorite(work.id.uuid) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun WorkCover(work: Work, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    // clip antes do clickable: o ripple segue o contorno arredondado do card inteiro (capa+título).
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            val cover = work.cover
            if (cover != null) {
                AsyncImage(
                    model = cover,
                    contentDescription = work.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
            }
            // Scrim circular translúcido garante contraste do ícone sobre qualquer capa.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onToggleFavorite)
                    .padding(5.dp),
            ) {
                Icon(
                    imageVector = if (work.favorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                    contentDescription = if (work.favorite) "Desfavoritar" else "Favoritar",
                    tint = if (work.favorite) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            work.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
