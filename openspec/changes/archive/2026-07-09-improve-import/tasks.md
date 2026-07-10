## 1. Shell do modal: `expect/actual` (bottom sheet no mobile, dialog no desktop)

- [x] 1.1 Extrair o miolo da revisão para `ImportContent(state, onCancel, onConfirm)` em `commonMain` (Loading/Reviewing/Error), independente do shell
- [x] 1.2 `commonMain`: `BottomSheetShell(dismissable, onDismiss, content)` (Material3 `ModalBottomSheet`, não-dispensável durante processamento) e `DialogShell(dismissable, onDismiss, content)` (comportamento atual)
- [x] 1.3 `expect @Composable fun ImportModalShell(dismissable, onDismiss, content)` + `actual` → `BottomSheetShell` em `androidMain`/`iosMain` e `DialogShell` em `jvmMain`
- [x] 1.4 `ImportDialog` passa a usar `ImportModalShell` (sem `Dialog` direto); `Hidden` não desenha nada

## 2. Rodapé fixo e insets de teclado

- [x] 2.1 Reestruturar `ReviewContent` em 3 faixas: header fixo · conteúdo `Modifier.weight(1f).verticalScroll` · footer fixo (remover `heightIn(max=420)` do miolo)
- [x] 2.2 Limitar a altura do modal à tela e aplicar `imePadding()` no container — a altura mora no **shell**: o `ModalBottomSheet` já se limita sozinho e o `DialogShell` aplica `heightIn(max = 90%)` medindo a janela **antes** de entrar no `Dialog` (dentro dele, `containerSize` é a do próprio dialog)
- [x] 2.3 `MainActivity` (Android host) chama `enableEdgeToEdge()` para insets de IME reais no sheet

## 3. Fechar o teclado (comum às 3 plataformas)

- [x] 3.1 `Modifier.pointerInput { detectTapGestures { focusManager.clearFocus() } }` no container do conteúdo (toque fora do campo baixa o IME, **sem** fechar o modal)
- [x] 3.2 `KeyboardOptions`/`KeyboardActions` nos campos: título `ImeAction.Next` → foca descrição; descrição `ImeAction.Done` → `clearFocus()`
- [x] 3.3 iOS: validar que o auto-avoid do `ComposeUIViewController` não conflita com `imePadding`/sheet (sem dupla compensação) — **validado em device**: sem dupla compensação, nenhum ajuste necessário

## 4. Capa como imagem autônoma (modelo)

- [x] 4.1 `WorkCover` acomoda proveniência **página** `{chapterId, entryName}` **ou** **externa** (sem referência de página); serialização forward-compatible do `work.json`
- [x] 4.2 `ImportEdits.cover` vira `CoverChoice` = `Page(chapterId, entryName) | External(handle dos bytes retidos)`
- [x] 4.3 `CoverStore.generate` (ou equivalente) passa a aceitar **bytes** da fonte escolhida em vez de extrair de uma página do OPZ; encode único `encodeThumbnail(512)` (novo `CoverStore.writeFromBytes`)
- [x] 4.4 `ContentImporter.commit` gera `cover.webp` a partir dos bytes da fonte (página ou externa) e grava `work.json.cover` com a proveniência correspondente (`materializeCover`)
- [x] 4.5 Retenção da imagem externa entre Reviewing e commit (bytes já codificados em memória no `CoverChoice.External`); descarte automático em `cancel`/troca de capa (sem temp em disco)

## 5. Galeria de capa: célula de imagem externa

- [x] 5.1 Célula "+" à direita da `LazyRow` de candidatas que abre o seletor de imagem do FileKit (`FileKitType.Image`)
- [x] 5.2 Preview da imagem externa escolhida na galeria (thumbnail via `encodeThumbnail`), destacada como as demais quando selecionada
- [x] 5.3 Selecionar imagem externa define `CoverChoice.External`; selecionar uma página volta para `CoverChoice.Page`
- [x] 5.4 [correção não prevista] Picker por **extensão** (`FileKitType.File(ImportFormats.coverImages)`, com `webp`) em vez de `FileKitType.Image` — o picker de galeria nativo filtra o webp (formato da própria capa) e não o oferece
- [x] 5.5 [correção não prevista] Desktop decodifica **webp**: plugin `imageio-webp` (TwelveMonkeys) em `jvmMain`, auto-registrado via `ServiceLoader` — sem ele `CoverEncoder.encodeThumbnail(webp)` devolvia `null` e a seleção "não fazia nada" (Android/iOS já decodificam webp nativamente)
- [x] 5.6 [correção não prevista] Não falhar em silêncio: quando a imagem escolhida não decodifica, um **modal de aviso** (sheet no mobile, dialog no desktop) sobe sobre a revisão; fechá-lo devolve o formulário com a capa anterior intacta. No `commit`, capa externa ilegível **falha o import** em vez de materializar a obra sem a capa escolhida
- [x] 5.7 [correção não prevista] Preview da capa externa não atualizava na 2ª seleção (cache do Coil por key fixa `"external-cover"` + `ThumbnailImage.equals` só por key): key derivada do conteúdo (`contentHashCode`), memoizada por seleção
- [x] 5.8 [correção não prevista] Re-selecionar a capa externa reabria o seletor: com imagem carregada, o card só **seleciona** (`selectExternalCover()`, sem reabrir o picker) e a **troca** vira botão no canto superior direito, dentro dos limites do card (`loadExternalCover(file)`); sem imagem, o card inteiro segue abrindo o seletor

## 6. Testes e verificação

- [x] 6.1 Teste: `commit` com capa externa → `cover.webp` gerada dos bytes externos e `work.json.cover` marcado como externo (`ImportReviewJvmTest.commit_comImagemExterna_*`)
- [x] 6.2 Teste: `commit` com capa de página → `work.json.cover = {chapterId, entryName}` e `cover.webp` gerada dessa página (regressão preservada)
- [x] 6.3 Teste: `cancel` não deixa temp/artefato (capa externa é em memória, sem temp em disco — coberto por `prepare_naoGravaNada_eCancelDescartaOrigem`)
- [x] 6.4 Teste: forward-compat de `work.json` — capa antiga `{chapterId, entryName}` sem `source` desserializa como `PAGE` (`WorkManifestJvmTest.read_capaAntigaSemSource_*`); capa externa desserializa
- [x] 6.5 Verificação manual: fluxo de capa externa testado **end to end** (seleção, troca sucessiva de imagem, webp no desktop, materialização); revisão em tela pequena/teclado exercitada
- [x] 6.6 `openspec validate improve-import --strict` passa; build compila nas 3 plataformas (JVM/desktop, Android, iOS) e testes JVM verdes
- [x] 6.7 [correção não prevista] Teste: `ImageIO` do desktop tem reader de `webp` registrado (`CoverStoreJvmTest.imageIO_temReaderWebp_noDesktop`) — a raiz do bug da capa `.webp`
- [x] 6.8 [correção não prevista] Teste: capa externa ilegível não é ignorada — `writeFromBytes` não grava nada (`CoverStoreJvmTest.writeFromBytes_imagemIlegivel_naoGravaCapa`) e o `commit` falha sem materializar a obra (`ImportReviewJvmTest.commit_comImagemExternaIlegivel_falhaSemMaterializar`)

## 7. Refactor da camada de UI/ViewModel (não previsto)

Sem mudança de comportamento: o estado da revisão vivia em `remember` dentro do composable e o
encode da capa externa rodava na composição (main thread). Migrado para o ViewModel.

- [x] 7.1 `ImportForm` (candidatas, título, descrição, capa, preview externo, erro) passa a ser o estado da revisão, carregado por `ImportUiState.Reviewing`
- [x] 7.2 `ReviewContent` vira **stateless**: recebe `form` + callbacks (`onTitleChange`, `onDescriptionChange`, `onSelectPage`, `onSelectExternal`, `onCancel`, `onConfirm`); só restam `remember` de UI (foco, scroll, picker)
- [x] 7.3 Rascunho retido num flow (`MutableStateFlow<ImportDraft?>`), separado do `_state` porque sobrevive às transições `Reviewing → Loading → Error` e é descartado em qualquer uma
- [x] 7.4 `selectExternalCover(file)` move `readBytes()` + `encodeThumbnail()` do composable para o ViewModel, em `withContext(ioDispatcher)` (antes rodava na main thread)
- [x] 7.5 `confirm()` deixa de receber `ImportEdits` (o ViewModel é dono do formulário); `try/catch` → `runCatching { }.onSuccess/onFailure`
- [x] 7.6 `updateForm { }` centraliza as edições e só aplica em `Reviewing` — um `selectExternalCover` que retorne tarde (já em `Loading`) não corrompe mais o estado
- [x] 7.7 Comentários narrativos removidos; mantidos só os que registram restrição não-óbvia (key de cache do Coil derivada do conteúdo; motivo de o `draft` ser flow à parte)
- [x] 7.8 Duplicação: moldura + rótulo das células extraídos em `CoverCell(label, selected, onClick) { thumbnail }`
- [x] 7.9 Compila nas 3 plataformas e testes JVM verdes após o refactor

## 8. Ajustes da verificação

- [x] 8.1 Capa externa grava os bytes **já reduzidos** na revisão, sem 2ª passada de decode/encode: `CoverStore.writeEncoded` valida com o novo `CoverEncoder.isDecodable` (3 `actual`) e grava direto — encode único de ponta a ponta
- [x] 8.2 Teste: `work.json` com `cover.source = EXTERNAL` desserializa sem `{chapterId, entryName}` (`WorkManifestJvmTest.capaExterna_roundtrip_semReferenciaDePagina`); `CoverStoreJvmTest.writeEncoded_*` cobre bytes intactos e ilegíveis
- [x] 8.3 Documentado por que o aviso de capa ilegível usa um `ImportModalShell` irmão (mesmo shell da plataforma) em vez de um `AlertDialog` sobre o sheet
