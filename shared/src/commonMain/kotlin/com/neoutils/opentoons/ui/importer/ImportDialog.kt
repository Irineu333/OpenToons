package com.neoutils.opentoons.ui.importer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.neoutils.opentoons.data.importer.CoverCandidate
import com.neoutils.opentoons.data.importer.CoverChoice
import com.neoutils.opentoons.domain.model.ThumbnailImage
import com.neoutils.opentoons.ui.icons.AppIcons
import com.neoutils.opentoons.util.ImportFormats
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/** Raio dos campos e das células de capa — arredondamento sutil, alinhado ao do shell (16.dp). */
private val FieldShape = RoundedCornerShape(8.dp)

/**
 * Modal de import: o shell é escolhido por plataforma ([ImportModalShell]) e o miolo é
 * compartilhado. Durante o processamento o modal não é dispensável (evita cancelar a meio de um
 * save). Fechado (`Hidden`) não desenha nada.
 *
 * O erro de capa ilegível ganha um shell próprio **irmão** (não aninhado) do da revisão: fechá-lo
 * devolve o usuário ao formulário com a capa anterior intacta.
 */
@Composable
fun ImportDialog(viewModel: ImportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state
    if (current is ImportUiState.Hidden) return

    ImportModalShell(
        dismissable = current is ImportUiState.Reviewing || current is ImportUiState.Error,
        onDismiss = viewModel::cancel,
    ) {
        ImportContent(current, viewModel)
    }

    val coverError = (current as? ImportUiState.Reviewing)?.form?.coverError
    if (coverError != null) {
        // Segundo `ImportModalShell` (no mobile, um sheet sobre o outro) em vez de um `AlertDialog`:
        // o aviso precisa ser o mesmo shell da plataforma que a revisão — dialog no desktop, sheet
        // no mobile — senão um `Dialog` sobre o bottom sheet quebra os insets de IME do sheet de trás.
        ImportModalShell(dismissable = true, onDismiss = viewModel::dismissCoverError) {
            MessageContent(
                title = "Não foi possível usar essa imagem",
                message = coverError,
                onDismiss = viewModel::dismissCoverError,
            )
        }
    }
}

@Composable
private fun ImportContent(state: ImportUiState, viewModel: ImportViewModel) {
    when (state) {
        is ImportUiState.Loading -> LoadingContent(state.message)
        is ImportUiState.Reviewing -> ReviewContent(
            form = state.form,
            onTitleChange = viewModel::updateTitle,
            onDescriptionChange = viewModel::updateDescription,
            onSelectPage = viewModel::selectPageCover,
            onSelectExternal = viewModel::selectExternalCover,
            onLoadExternal = viewModel::loadExternalCover,
            onCancel = viewModel::cancel,
            onConfirm = viewModel::confirm,
        )

        is ImportUiState.Error -> MessageContent(
            title = "Não foi possível importar",
            message = state.message,
            onDismiss = viewModel::cancel,
        )
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

/** Mensagem de falha com um único botão de saída — serve ao erro do import e ao da capa. */
@Composable
private fun MessageContent(title: String, message: String, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

/**
 * Revisão de metadados antes de materializar. Layout de 3 faixas — header fixo, conteúdo rolável,
 * footer fixo — para que as ações nunca sejam empurradas para fora em telas pequenas; `imePadding`
 * mantém o footer acima do teclado. Tocar fora dos campos baixa o teclado (no iOS não há botão de
 * sistema para isso). A capa vem de uma página da obra ou de uma imagem externa.
 */
@Composable
private fun ReviewContent(
    form: ImportForm,
    onTitleChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSelectPage: (CoverCandidate) -> Unit,
    onSelectExternal: () -> Unit,
    onLoadExternal: (PlatformFile) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val descriptionFocus = remember { FocusRequester() }

    val imagePicker = rememberFilePickerLauncher(
        type = FileKitType.File(ImportFormats.coverImages),
    ) { picked -> picked?.let(onLoadExternal) }

    // Sem altura própria: quem limita o modal à tela é o shell (sheet no mobile, dialog no desktop).
    Column(Modifier.fillMaxWidth().imePadding()) {
        Text(
            "Revisar import",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 16.dp),
        )

        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }
                .padding(horizontal = 24.dp),
        ) {
            OutlinedTextField(
                value = form.title,
                onValueChange = onTitleChange,
                label = { Text("Nome da obra") },
                singleLine = true,
                shape = FieldShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { descriptionFocus.requestFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = form.description,
                onValueChange = onDescriptionChange,
                label = { Text("Descrição (opcional)") },
                minLines = 3,
                shape = FieldShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(descriptionFocus),
            )

            Spacer(Modifier.height(16.dp))
            Text("Capa", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(form.candidates, key = { it.chapterId }) { candidate ->
                    CoverCandidateCell(
                        thumbnail = candidate.thumbnail,
                        cacheKey = candidate.chapterId,
                        label = candidate.chapterTitle,
                        selected = (form.cover as? CoverChoice.Page)?.chapterId == candidate.chapterId,
                        onClick = { onSelectPage(candidate) },
                    )
                }
                item {
                    ExternalCoverCell(
                        preview = form.externalCover,
                        selected = form.cover is CoverChoice.External,
                        onSelect = onSelectExternal,
                        onPick = { imagePicker.launch() },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    vertical = 8.dp,
                    horizontal = 24.dp,
                ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("Cancelar")
            }
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text("Importar")
            }
        }
    }
}

@Composable
private fun CoverCandidateCell(
    thumbnail: ByteArray?,
    cacheKey: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    CoverCell(label = label, selected = selected, onClick = onClick) {
        if (thumbnail != null) {
            AsyncImage(
                model = ThumbnailImage(key = cacheKey, bytes = thumbnail),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxSize()) {}
        }
    }
}

/**
 * Célula de imagem externa. Sem imagem, o card inteiro abre o seletor. Com imagem, o card apenas
 * **seleciona** a capa e a troca fica no botão do canto superior direito — clicar no card para
 * escolher a imagem já carregada não deve reabrir o seletor.
 */
@Composable
private fun ExternalCoverCell(
    preview: ByteArray?,
    selected: Boolean,
    onSelect: () -> Unit,
    onPick: () -> Unit,
) {
    CoverCell(
        label = "Imagem",
        selected = selected,
        onClick = if (preview != null) onSelect else onPick,
    ) {
        if (preview != null) {
            // Key derivada do conteúdo: com key fixa o Coil serviria a imagem anterior do cache.
            val cacheKey = remember(preview) { "external-cover-${preview.contentHashCode()}" }
            AsyncImage(
                model = ThumbnailImage(key = cacheKey, bytes = preview),
                contentDescription = "Capa externa",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onPick)
                    .padding(4.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Edit,
                    contentDescription = "Trocar imagem",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "+",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Célula da galeria de capa: moldura com aspecto de capa, borda de seleção, clique e rótulo. */
@Composable
private fun CoverCell(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    thumbnail: @Composable BoxScope.() -> Unit,
) {
    Column(
        Modifier.width(96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(FieldShape)
                .border(
                    border = BorderStroke(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    shape = FieldShape,
                )
                .clickable(onClick = onClick),
        ) {
            thumbnail()
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
