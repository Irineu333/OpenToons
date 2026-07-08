package com.neoutils.opentoons.ui.importer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neoutils.opentoons.data.importer.CoverCandidate
import com.neoutils.opentoons.data.importer.CoverChoice
import com.neoutils.opentoons.data.importer.ImportDraft
import com.neoutils.opentoons.data.importer.ImportEdits
import com.neoutils.opentoons.domain.model.ThumbnailImage
import com.neoutils.opentoons.util.CoverEncoder
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.launch

/**
 * Modal de import (improve-import): dono do fluxo de revisão de metadados, isolado da biblioteca.
 * O **shell** é escolhido por plataforma — `ModalBottomSheet` no mobile, `Dialog` no desktop
 * ([ImportModalShell]) — e o miolo ([ImportContent]) é 100% compartilhado. Fechado (`Hidden`) não
 * desenha nada. Durante o processamento o modal não é dispensável (evita cancelar a meio de um save).
 */
@Composable
fun ImportDialog(viewModel: ImportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    if (current is ImportUiState.Hidden) return

    val dismissable = current is ImportUiState.Reviewing || current is ImportUiState.Error
    ImportModalShell(
        dismissable = dismissable,
        onDismiss = { viewModel.cancel() },
    ) {
        ImportContent(
            state = current,
            onCancel = { viewModel.cancel() },
            onConfirm = { edits -> viewModel.confirm(edits) },
        )
    }
}

/** Miolo do modal (independente do shell): despacha por estado. */
@Composable
private fun ImportContent(
    state: ImportUiState,
    onCancel: () -> Unit,
    onConfirm: (ImportEdits) -> Unit,
) {
    when (state) {
        is ImportUiState.Loading -> LoadingContent(state.message)
        is ImportUiState.Reviewing -> ReviewContent(state.draft, onCancel, onConfirm)
        is ImportUiState.Error -> ErrorContent(state.message, onCancel)
        ImportUiState.Hidden -> Unit
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
 * Revisão de metadados antes de materializar: nome, descrição e capa editáveis. Layout de 3 faixas
 * — **header fixo · conteúdo rolável · footer fixo** — de modo que as ações (Cancelar/Importar)
 * nunca são empurradas para fora em telas pequenas; `imePadding` mantém o footer acima do teclado.
 * Tocar em qualquer área fora dos campos baixa o teclado (essencial no iOS, sem botão de sistema).
 *
 * A capa pode vir das **páginas da própria obra** (1ª de cada capítulo) **ou** de uma **imagem
 * externa** (célula "+" à direita), preenchendo o mesmo slot autônomo — para CBZ sem capa própria.
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
    // Thumbnail (já codificada) da imagem externa escolhida — preview na galeria e bytes do commit.
    var externalPreview by remember(draft) { mutableStateOf<ByteArray?>(null) }

    val focusManager = LocalFocusManager.current
    val descriptionFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    val imagePicker = rememberFilePickerLauncher(type = FileKitType.Image) { picked ->
        picked ?: return@rememberFilePickerLauncher
        scope.launch {
            val thumb = CoverEncoder.encodeThumbnail(picked.readBytes()) ?: return@launch
            externalPreview = thumb
            cover = CoverChoice.External(thumb)
        }
    }

    // Teto = ~90% da tela; conteúdo cede espaço (weight fill=false) e rola quando estoura.
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val maxHeight = with(LocalDensity.current) {
        if (windowHeight > 0) (windowHeight * 0.9f).toDp() else Dp.Unspecified
    }

    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .imePadding(),
    ) {
        Text(
            "Revisar import",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        )

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                // Tocar fora dos campos baixa o teclado (sem fechar o modal).
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(horizontal = 24.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Nome da obra") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Descrição (opcional)") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus),
            )

            Spacer(Modifier.height(16.dp))
            Text("Capa", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(draft.coverCandidates, key = { it.chapterId }) { candidate ->
                    val selected = (cover as? CoverChoice.Page)?.chapterId == candidate.chapterId
                    CoverCandidateCell(
                        thumbnail = candidate.thumbnail,
                        key = candidate.chapterId,
                        label = candidate.chapterTitle,
                        selected = selected,
                        onClick = { cover = CoverChoice.Page(candidate.chapterId, candidate.entryName) },
                    )
                }
                item {
                    ExternalCoverCell(
                        preview = externalPreview,
                        selected = cover is CoverChoice.External,
                        onClick = { imagePicker.launch() },
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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

/** Célula da galeria de capa: thumbnail (de página ou externa) + destaque quando selecionada. */
@Composable
private fun CoverCandidateCell(
    thumbnail: ByteArray?,
    key: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverThumbBox(selected = selected, onClick = onClick) {
            if (thumbnail != null) {
                AsyncImage(
                    model = ThumbnailImage(key = key, bytes = thumbnail),
                    contentDescription = label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Célula de **imagem externa**: mostra o preview escolhido, ou um "+" para abrir o seletor. */
@Composable
private fun ExternalCoverCell(
    preview: ByteArray?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CoverThumbBox(selected = selected, onClick = onClick) {
            if (preview != null) {
                AsyncImage(
                    model = ThumbnailImage(key = "external-cover", bytes = preview),
                    contentDescription = "Capa externa",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            "Imagem",
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Moldura comum das células de capa: aspecto de capa, borda de seleção e clique. */
@Composable
private fun CoverThumbBox(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
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
        content()
    }
}
