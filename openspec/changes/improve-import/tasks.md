## 1. Shell do modal: `expect/actual` (bottom sheet no mobile, dialog no desktop)

- [ ] 1.1 Extrair o miolo da revisão para `ImportContent(state, onCancel, onConfirm)` em `commonMain` (Loading/Reviewing/Error), independente do shell
- [ ] 1.2 `commonMain`: `BottomSheetShell(dismissable, onDismiss, content)` (Material3 `ModalBottomSheet`, não-dispensável durante processamento) e `DialogShell(dismissable, onDismiss, content)` (comportamento atual)
- [ ] 1.3 `expect @Composable fun ImportModalShell(dismissable, onDismiss, content)` + `actual` → `BottomSheetShell` em `androidMain`/`iosMain` e `DialogShell` em `jvmMain`
- [ ] 1.4 `ImportDialog` passa a usar `ImportModalShell` (sem `Dialog` direto); `Hidden` não desenha nada

## 2. Rodapé fixo e insets de teclado

- [ ] 2.1 Reestruturar `ReviewContent` em 3 faixas: header fixo · conteúdo `Modifier.weight(1f).verticalScroll` · footer fixo (remover `heightIn(max=420)` do miolo)
- [ ] 2.2 Limitar a altura do modal à tela (`heightIn(max = ~90%)`) e aplicar `imePadding()` no container
- [ ] 2.3 `MainActivity` (Android host) chama `enableEdgeToEdge()` para insets de IME reais no sheet

## 3. Fechar o teclado (comum às 3 plataformas)

- [ ] 3.1 `Modifier.pointerInput { detectTapGestures { focusManager.clearFocus() } }` no container do conteúdo (toque fora do campo baixa o IME, **sem** fechar o modal)
- [ ] 3.2 `KeyboardOptions`/`KeyboardActions` nos campos: título `ImeAction.Next` → foca descrição; descrição `ImeAction.Done` → `clearFocus()`
- [ ] 3.3 iOS: validar que o auto-avoid do `ComposeUIViewController` não conflita com `imePadding`/sheet (sem dupla compensação); ajustar se necessário

## 4. Capa como imagem autônoma (modelo)

- [ ] 4.1 `WorkCover` acomoda proveniência **página** `{chapterId, entryName}` **ou** **externa** (sem referência de página); serialização forward-compatible do `work.json`
- [ ] 4.2 `ImportEdits.cover` vira `CoverChoice` = `Page(chapterId, entryName) | External(handle dos bytes retidos)`
- [ ] 4.3 `CoverStore.generate` (ou equivalente) passa a aceitar **bytes** da fonte escolhida em vez de extrair de uma página do OPZ; encode único `encodeThumbnail(512)`
- [ ] 4.4 `ContentImporter.commit` gera `cover.webp` a partir dos bytes da fonte (página ou externa) e grava `work.json.cover` com a proveniência correspondente
- [ ] 4.5 Retenção da imagem externa entre Reviewing e commit (temp retido, como `sourceTemp`); descarte em `cancel`/troca de capa/`cleanupOrphanTemps`

## 5. Galeria de capa: célula de imagem externa

- [ ] 5.1 Célula "+" à direita da `LazyRow` de candidatas que abre o seletor de imagem do FileKit (imagem única)
- [ ] 5.2 Preview da imagem externa escolhida na galeria (thumbnail via `encodeThumbnail`), destacada como as demais quando selecionada
- [ ] 5.3 Selecionar imagem externa define `CoverChoice.External`; selecionar uma página volta para `CoverChoice.Page`

## 6. Testes e verificação

- [ ] 6.1 Teste: `commit` com capa externa → `cover.webp` gerada dos bytes externos e `work.json.cover` marcado como externo
- [ ] 6.2 Teste: `commit` com capa de página → `work.json.cover = {chapterId, entryName}` e `cover.webp` gerada dessa página (regressão preservada)
- [ ] 6.3 Teste: `cancel` após escolher imagem externa não deixa temp externo nem artefato em disco/banco
- [ ] 6.4 Teste: forward-compat de `work.json` — capa de página antiga desserializa; capa externa nova desserializa
- [ ] 6.5 Verificação manual: em tela pequena e com teclado aberto, Cancelar/Importar visíveis; tocar fora baixa o teclado sem fechar (Android, iOS, desktop)
- [ ] 6.6 `openspec validate improve-import --strict` passa; build/tests verdes nas plataformas
