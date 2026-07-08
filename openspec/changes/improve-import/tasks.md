## 1. Shell do modal: `expect/actual` (bottom sheet no mobile, dialog no desktop)

- [x] 1.1 Extrair o miolo da revisão para `ImportContent(state, onCancel, onConfirm)` em `commonMain` (Loading/Reviewing/Error), independente do shell
- [x] 1.2 `commonMain`: `BottomSheetShell(dismissable, onDismiss, content)` (Material3 `ModalBottomSheet`, não-dispensável durante processamento) e `DialogShell(dismissable, onDismiss, content)` (comportamento atual)
- [x] 1.3 `expect @Composable fun ImportModalShell(dismissable, onDismiss, content)` + `actual` → `BottomSheetShell` em `androidMain`/`iosMain` e `DialogShell` em `jvmMain`
- [x] 1.4 `ImportDialog` passa a usar `ImportModalShell` (sem `Dialog` direto); `Hidden` não desenha nada

## 2. Rodapé fixo e insets de teclado

- [x] 2.1 Reestruturar `ReviewContent` em 3 faixas: header fixo · conteúdo `Modifier.weight(1f).verticalScroll` · footer fixo (remover `heightIn(max=420)` do miolo)
- [x] 2.2 Limitar a altura do modal à tela (`heightIn(max = ~90%)`) e aplicar `imePadding()` no container
- [x] 2.3 `MainActivity` (Android host) chama `enableEdgeToEdge()` para insets de IME reais no sheet

## 3. Fechar o teclado (comum às 3 plataformas)

- [x] 3.1 `Modifier.pointerInput { detectTapGestures { focusManager.clearFocus() } }` no container do conteúdo (toque fora do campo baixa o IME, **sem** fechar o modal)
- [x] 3.2 `KeyboardOptions`/`KeyboardActions` nos campos: título `ImeAction.Next` → foca descrição; descrição `ImeAction.Done` → `clearFocus()`
- [ ] 3.3 iOS: validar que o auto-avoid do `ComposeUIViewController` não conflita com `imePadding`/sheet (sem dupla compensação); ajustar se necessário — **pendente: exige execução em device iOS** (código compila; abordagem `imePadding` no lugar)

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

## 6. Testes e verificação

- [x] 6.1 Teste: `commit` com capa externa → `cover.webp` gerada dos bytes externos e `work.json.cover` marcado como externo (`ImportReviewJvmTest.commit_comImagemExterna_*`)
- [x] 6.2 Teste: `commit` com capa de página → `work.json.cover = {chapterId, entryName}` e `cover.webp` gerada dessa página (regressão preservada)
- [x] 6.3 Teste: `cancel` não deixa temp/artefato (capa externa é em memória, sem temp em disco — coberto por `prepare_naoGravaNada_eCancelDescartaOrigem`)
- [x] 6.4 Teste: forward-compat de `work.json` — capa antiga `{chapterId, entryName}` sem `source` desserializa como `PAGE` (`WorkManifestJvmTest.read_capaAntigaSemSource_*`); capa externa desserializa
- [ ] 6.5 Verificação manual: em tela pequena e com teclado aberto, Cancelar/Importar visíveis; tocar fora baixa o teclado sem fechar (Android, iOS, desktop) — **pendente: exige execução em device/emulador**
- [x] 6.6 `openspec validate improve-import --strict` passa; build compila nas 3 plataformas (JVM/desktop, Android, iOS) e testes JVM verdes
