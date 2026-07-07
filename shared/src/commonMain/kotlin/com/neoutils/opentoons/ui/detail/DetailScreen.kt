package com.neoutils.opentoons.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import com.neoutils.opentoons.util.ImportFormats
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/**
 * Tela de detalhe da obra (spec offline-library). Capa, metadados, lista de capítulos e
 * "continuar leitura". Agora também: **adicionar capítulos** (CBZ/CBR — task 5.1) e
 * **selecionar/remover capítulos** por pressionar-e-segurar (tasks 5.2–5.4).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val work by viewModel.work.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()

    var showDeleteWork by remember { mutableStateOf(false) }
    var showDeleteChapters by remember { mutableStateOf(false) }
    var showEmptied by remember { mutableStateOf(false) }

    val selectionMode = selected.isNotEmpty()

    // Adicionar capítulos: só unidade (CBZ/CBR); pacotes recusados pelo importer (task 5.1).
    // CBR só onde a plataforma descompacta RAR (no iOS fica só CBZ — D5).
    val addPicker = rememberFilePickerLauncher(
        type = FileKitType.File(ImportFormats.unit),
    ) { file ->
        file?.let(viewModel::addChapters)
    }

    Scaffold(
        floatingActionButton = {
            if (chapters.isNotEmpty() && !selectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.continueReading { ch -> ch?.let { onOpenChapter(it.id) } } },
                    text = { Text("Continuar leitura") },
                    icon = { Text("▶") },
                )
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            if (selectionMode) {
                SelectionBar(
                    count = selected.size,
                    onClose = { viewModel.clearSelection() },
                    onDelete = { showDeleteChapters = true },
                )
            } else {
                TopBar(
                    title = work?.title ?: "",
                    isFavorite = work?.favorite == true,
                    onBack = onBack,
                    onAddChapters = { addPicker.launch() },
                    onToggleFavorite = { viewModel.toggleFavorite() },
                    onDeleteWork = { showDeleteWork = true },
                )
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // Tela inteira rola como superfície única: cabeçalho e capítulos na mesma
            // LazyColumn (sem Column(verticalScroll) aninhando a lista). A TopBar/SelectionBar
            // e o bloco de busy acima ficam fixos, com as ações sempre acessíveis.
            LazyColumn(Modifier.fillMaxSize()) {
                item {
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
                            // heightIn(min = capa): quando a descrição é curta, o Spacer com peso
                            // empurra as labels para a base (alinhadas ao rodapé da capa); quando
                            // longa, o bloco cresce e as labels seguem logo abaixo do texto.
                            Column(
                                Modifier
                                    .padding(start = 16.dp)
                                    .weight(1f)
                                    .heightIn(min = 155.dp),
                            ) {
                                Text(work?.title ?: "", style = MaterialTheme.typography.titleLarge)
                                // Descrição (dado do work.json, editada no import): bloco próprio,
                                // com espaço para textos longos; omitida quando vazia.
                                work?.description?.takeIf { it.isNotBlank() }?.let { description ->
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                                Spacer(Modifier.weight(1f).height(12.dp))

                                // Demais detalhes viram labels compactas, separadas da descrição.
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MetaLabel(work?.effectiveDirection?.name ?: "-")
                                    MetaLabel("${chapters.size} cap.")
                                }
                            }
                        }
                    }
                }

                item {
                    // Alinha em 16.dp com a margem do cabeçalho e com o conteúdo das linhas.
                    Text(
                        "Capítulos",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }

                items(chapters, key = { it.id }) { chapter ->
                    ChapterRow(
                        chapter = chapter,
                        selectionMode = selectionMode,
                        isSelected = chapter.id in selected,
                        onClick = {
                            if (selectionMode) viewModel.toggleSelection(chapter.id)
                            else onOpenChapter(chapter.id)
                        },
                        onLongClick = { viewModel.startSelection(chapter.id) },
                    )
                }
            }
        }
    }

    if (showDeleteWork) {
        ConfirmDialog(
            title = "Excluir obra",
            body = "Remove \"${work?.title ?: ""}\" e a pasta da obra (todos os capítulos) do " +
                "dispositivo. Não pode ser desfeito.",
            confirmLabel = "Excluir",
            onConfirm = {
                showDeleteWork = false
                viewModel.delete(onDeleted = onBack)
            },
            onDismiss = { showDeleteWork = false },
        )
    }

    if (showDeleteChapters) {
        ConfirmDialog(
            title = "Remover capítulos",
            body = "Remove ${selected.size} capítulo(s) selecionado(s) e o progresso associado. " +
                "Não pode ser desfeito.",
            confirmLabel = "Remover",
            onConfirm = {
                showDeleteChapters = false
                viewModel.removeSelected(onWorkEmptied = { showEmptied = true })
            },
            onDismiss = { showDeleteChapters = false },
        )
    }

    if (showEmptied) {
        // Último capítulo removido (task 5.4): o usuário decide remover a obra ou mantê-la vazia.
        ConfirmDialog(
            title = "Obra sem capítulos",
            body = "Todos os capítulos foram removidos. Deseja remover a obra da biblioteca?",
            confirmLabel = "Remover obra",
            onConfirm = {
                showEmptied = false
                viewModel.delete(onDeleted = onBack)
            },
            onDismiss = { showEmptied = false },
        )
    }

    // Erro do fluxo de adicionar capítulos (ex.: pacote recusado, RAR no iOS): status != null
    // já sem estar ocupado significa mensagem terminal.
    if (status != null && !busy) {
        AlertDialog(
            onDismissRequest = { viewModel.clearStatus() },
            title = { Text("Não foi possível adicionar") },
            text = { Text(status ?: "") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearStatus() }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun TopBar(
    title: String,
    isFavorite: Boolean,
    onBack: () -> Unit,
    onAddChapters: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDeleteWork: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(imageVector = AppIcons.ArrowBack, contentDescription = "Voltar")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onAddChapters) {
            Icon(imageVector = AppIcons.Add, contentDescription = "Adicionar capítulos")
        }
        IconButton(onClick = onToggleFavorite) {
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
        IconButton(onClick = onDeleteWork) {
            Icon(
                imageVector = AppIcons.Delete,
                contentDescription = "Excluir",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SelectionBar(count: Int, onClose: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(imageVector = AppIcons.Close, contentDescription = "Cancelar seleção")
        }
        Text(
            "$count selecionado(s)",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = AppIcons.Delete,
                contentDescription = "Remover capítulos",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChapterRow(
    chapter: Chapter,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (selectionMode) {
            Checkbox(checked = isSelected, onCheckedChange = { onClick() })
            Spacer(Modifier.size(8.dp))
        }
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
        if (chapter.read && !selectionMode) Text("lido", style = MaterialTheme.typography.labelSmall)
    }
}

/** Label compacta (pílula) para um detalhe da obra: direção, quantidade de capítulos etc. */
@Composable
private fun MetaLabel(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}
