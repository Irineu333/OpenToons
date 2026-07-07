package com.neoutils.opentoons.ui.importer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neoutils.opentoons.data.importer.CoverCandidate
import com.neoutils.opentoons.data.importer.ImportDraft
import com.neoutils.opentoons.data.importer.ImportEdits
import com.neoutils.opentoons.data.local.work.WorkCover
import com.neoutils.opentoons.domain.model.ThumbnailImage

/**
 * Modal de import (edit-import-metadata): dono do fluxo de revisão de metadados, isolado da
 * biblioteca. Renderizado a partir do [ImportViewModel] — processa o arquivo, mostra o
 * formulário (título/descrição/capa) e materializa ao confirmar. Fechado (`Hidden`) não desenha
 * nada. Durante o processamento o modal não é dispensável (evita cancelar a meio de um save).
 */
@Composable
fun ImportDialog(viewModel: ImportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    if (current is ImportUiState.Hidden) return

    val dismissable = current is ImportUiState.Reviewing || current is ImportUiState.Error
    Dialog(
        onDismissRequest = { viewModel.cancel() },
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
            when (current) {
                is ImportUiState.Loading -> LoadingContent(current.message)
                is ImportUiState.Reviewing -> ReviewContent(
                    draft = current.draft,
                    onCancel = { viewModel.cancel() },
                    onConfirm = { edits -> viewModel.confirm(edits) },
                )
                is ImportUiState.Error -> ErrorContent(
                    message = current.message,
                    onDismiss = { viewModel.cancel() },
                )
                ImportUiState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun LoadingContent(message: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorContent(message: String, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Não foi possível importar", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/**
 * Revisão de metadados antes de materializar: nome, descrição e capa editáveis. **Cancelar**
 * descarta a origem (nada gravado); **Importar** materializa com os valores editados. A capa é
 * escolhida entre as páginas da própria obra (v1: 1ª de cada capítulo), preservando a referência
 * {chapterId, entryName}.
 */
@Composable
private fun ReviewContent(
    draft: ImportDraft,
    onCancel: () -> Unit,
    onConfirm: (ImportEdits) -> Unit,
) {
    var title by remember(draft) { mutableStateOf(draft.defaultTitle) }
    var description by remember(draft) { mutableStateOf("") }
    var cover by remember(draft) { mutableStateOf(draft.defaultCover) }

    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text("Revisar import", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Nome da obra") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Descrição (opcional)") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            if (draft.coverCandidates.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("Capa", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(draft.coverCandidates, key = { it.chapterId }) { candidate ->
                        CoverCandidateCell(
                            candidate = candidate,
                            selected = cover?.chapterId == candidate.chapterId,
                            onClick = { cover = WorkCover(candidate.chapterId, candidate.entryName) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancelar")
            }
            Button(
                onClick = { onConfirm(ImportEdits(title = title, description = description, cover = cover)) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Importar")
            }
        }
    }
}

/** Célula da galeria de capa: thumbnail da candidata + destaque quando selecionada. */
@Composable
private fun CoverCandidateCell(
    candidate: CoverCandidate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(RoundedCornerShape(8.dp))
                .border(
                    border = BorderStroke(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shape = RoundedCornerShape(8.dp),
                )
                .clickable(onClick = onClick),
        ) {
            val thumbnail = candidate.thumbnail
            if (thumbnail != null) {
                AsyncImage(
                    model = ThumbnailImage(key = candidate.chapterId, bytes = thumbnail),
                    contentDescription = candidate.chapterTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
            }
        }
        Text(
            candidate.chapterTitle,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
