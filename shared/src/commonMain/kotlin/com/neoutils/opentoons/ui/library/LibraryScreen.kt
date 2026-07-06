package com.neoutils.opentoons.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/**
 * Biblioteca em grid de capas (tela inicial — spec offline-library, tasks 7.1/7.2). Importa
 * via FileKit filtrado por `cbz/cbr/zip` (task 3.2). Favoritar por tap na estrela.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenWork: (String) -> Unit,
) {
    val works by viewModel.works.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()

    val picker = rememberFilePickerLauncher(
        type = FileKitType.File(listOf("cbz", "cbr", "zip")),
    ) { file ->
        file?.let(viewModel::import)
    }

    Column(Modifier.fillMaxSize().safeContentPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Biblioteca", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { picker.launch() }, enabled = !importing) {
                Text(if (importing) "Importando…" else "Importar")
            }
        }

        if (importing) {
            CircularProgressIndicator(
                Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
            )
        }

        if (works.isEmpty() && !importing) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhuma obra ainda.\nToque em Importar para adicionar um CBZ/CBR/ZIP.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(works, key = { it.id.uuid }) { work ->
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

@Composable
private fun WorkCover(work: Work, onClick: () -> Unit, onToggleFavorite: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
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
            Text(
                text = if (work.favorite) "★" else "☆",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clickable(onClick = onToggleFavorite),
            )
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
