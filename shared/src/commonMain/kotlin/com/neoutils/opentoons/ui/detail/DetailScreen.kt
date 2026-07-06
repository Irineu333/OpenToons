package com.neoutils.opentoons.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neoutils.opentoons.domain.model.Chapter
import com.neoutils.opentoons.ui.icons.AppIcons

/**
 * Tela de detalhe da obra (spec offline-library, task 7.3): capa, metadados, lista de
 * capítulos e "continuar leitura". A partir daqui o usuário abre o leitor.
 */
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val work by viewModel.work.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    var showDelete by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            if (chapters.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.continueReading { ch -> ch?.let { onOpenChapter(it.id) } } },
                    text = { Text("Continuar leitura") },
                    icon = { Text("▶") },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = AppIcons.ArrowBack, contentDescription = "Voltar")
                }
                Text(
                    work?.title ?: "",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                // Favoritar e excluir: dois IconButtons consistentes (task 7.2).
                val isFavorite = work?.favorite == true
                IconButton(onClick = { viewModel.toggleFavorite() }) {
                    Icon(
                        imageVector = if (isFavorite) AppIcons.Favorite else AppIcons.FavoriteBorder,
                        contentDescription = if (isFavorite) "Desfavoritar" else "Favoritar",
                        tint = if (isFavorite) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { showDelete = true }) {
                    Icon(
                        imageVector = AppIcons.Delete,
                        contentDescription = "Excluir",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Cabeçalho num painel elevado (surfaceContainerHigh) para destacar do fundo.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp)) {
                    val cover = work?.cover
                    Surface(
                        modifier = Modifier.size(width = 110.dp, height = 155.dp),
                        shape = RoundedCornerShape(8.dp),
                        tonalElevation = 4.dp,
                        shadowElevation = 2.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        if (cover != null) {
                            AsyncImage(
                                model = cover,
                                contentDescription = work?.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.padding(start = 16.dp).weight(1f)) {
                        Text(work?.title ?: "", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Direção: ${work?.direction?.name ?: "-"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${chapters.size} capítulo(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()
            Text(
                "Capítulos",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(16.dp),
            )

            LazyColumn(Modifier.fillMaxSize()) {
                items(chapters, key = { it.id }) { chapter ->
                    ChapterRow(chapter, onClick = { onOpenChapter(chapter.id) })
                    HorizontalDivider()
                }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Excluir obra") },
            text = {
                Text(
                    "Remove \"${work?.title ?: ""}\" e o arquivo importado do dispositivo. " +
                        "Não pode ser desfeito.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text("Excluir", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun ChapterRow(chapter: Chapter, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                chapter.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.Medium,
                color = if (chapter.read) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                "${chapter.pageCount} páginas",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (chapter.read) Text("lido", style = MaterialTheme.typography.labelSmall)
    }
}
