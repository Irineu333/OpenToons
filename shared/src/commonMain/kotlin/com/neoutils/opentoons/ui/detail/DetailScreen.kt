package com.neoutils.opentoons.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neoutils.opentoons.domain.model.Chapter

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

    Column(Modifier.fillMaxSize().safeContentPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ Voltar") }
            Text(
                work?.title ?: "",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            TextButton(onClick = { viewModel.toggleFavorite() }) {
                Text(if (work?.favorite == true) "★ Favorito" else "☆ Favoritar")
            }
        }

        Row(Modifier.fillMaxWidth().padding(16.dp)) {
            val cover = work?.cover
            Box(Modifier.size(width = 110.dp, height = 155.dp).clip(RoundedCornerShape(8.dp))) {
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = work?.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
                }
            }
            Column(Modifier.padding(start = 16.dp).weight(1f)) {
                Text(work?.title ?: "", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
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
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { viewModel.continueReading { ch -> ch?.let { onOpenChapter(it.id) } } },
                    enabled = chapters.isNotEmpty(),
                ) { Text("Continuar leitura") }
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
