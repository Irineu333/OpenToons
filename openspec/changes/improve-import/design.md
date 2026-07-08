# Design — improve-import

## D1. Shell do modal: `ModalBottomSheet` no mobile, `Dialog` no desktop

**Problema.** O `Dialog` atual não limita a altura à tela e só o miolo rola → a `Row` de ações
cai fora da dobra. E `imePadding()` **não funciona dentro de um `Dialog`** no Android sem forçar
`decorFitsSystemWindows=false` na janela do próprio dialog (pegadinha conhecida). O
`ModalBottomSheet` do Material3 já é ciente de IME de fábrica no Android — resolve os dois de uma vez.

**Decisão.** Extrair o shell para `expect/actual`, mantendo **todo o miolo** (`Loading`/`Reviewing`/
`Error`) em `commonMain`. Não existe source set "mobile" compartilhado entre `androidMain` e
`iosMain` — mas tanto `ModalBottomSheet` quanto `Dialog` são API multiplataforma do Compose, então
os dois shells vivem em `commonMain` e os `actual` só escolhem qual usar:

```
commonMain
 ├─ ImportContent(state, onCancel, onConfirm)   // miolo: header/conteúdo/footer, clearFocus, ImeAction
 ├─ @Composable BottomSheetShell(dismissable, onDismiss, content)   // escrito 1x
 ├─ @Composable DialogShell(dismissable, onDismiss, content)        // escrito 1x
 └─ expect @Composable fun ImportModalShell(dismissable, onDismiss, content)

androidMain:  actual ImportModalShell → BottomSheetShell   // one-liner
iosMain:      actual ImportModalShell → BottomSheetShell   // one-liner
jvmMain:      actual ImportModalShell → DialogShell        // one-liner
```

Nenhum source set novo no Gradle; nenhum código de layout duplicado; os `actual` são delegações.

**Layout de 3 faixas** (mata o botão sumido, tanto no sheet quanto no dialog):

```
Container( .fillMaxWidth().heightIn(max = ~90% da tela).imePadding() )
 └─ Column
     ├─ Header  "Revisar import"                (fixo)
     ├─ Content Modifier.weight(1f).verticalScroll   ← só isto cede espaço e rola
     │    campos + galeria de capa
     └─ Footer  [ Cancelar | Importar ]         (fixo, SEMPRE visível)
```

Com `weight(1f)` no conteúdo (em vez de `heightIn(max=420)` no miolo), quem cede espaço é o
conteúdo — o footer vira âncora e nunca é empurrado para fora.

**Regras preservadas.** Durante `Loading` o modal continua **não-dispensável**
(`dismissOnClickOutside/BackPress = false`); `Reviewing`/`Error` seguem dispensáveis. No
`ModalBottomSheet` isso mapeia para `confirmValueChange`/`sheetState` bloqueando o dismiss durante
o processamento.

## D2. Fechar o teclado (todas as plataformas)

`focusManager.clearFocus()` dispensa o IME em Android, iOS e desktop — é o mecanismo cross-platform
(não usar hack de teclado por plataforma). Fica **no miolo comum**, então vale para os três shells:

- **Toque de fundo**: `Modifier.pointerInput(Unit) { detectTapGestures { focusManager.clearFocus() } }`
  no container do conteúdo. Toque em área não-campo **só baixa o teclado** — não fecha o modal
  (fechar continua sendo scrim/back, regra do shell).
- **Campos**: `KeyboardOptions(imeAction = ImeAction.Next)` no título → foca a descrição;
  descrição com `ImeAction.Done` + `KeyboardActions(onDone = { focusManager.clearFocus() })`.

**iOS.** Não há botão de sistema para baixar o teclado, então o toque-de-fundo é a **única** saída —
por isso mora no comum, não é só conveniência de Android. O `ComposeUIViewController` do iOS sobe o
campo focado acima do teclado por conta própria; com o layout `imePadding` + sheet, validar que não
há dupla-compensação (conteúdo pulando). Se brigar, desligar o auto-avoid do CMP e confiar no
`imePadding` do sheet.

**Android host.** `MainActivity` passa a chamar `enableEdgeToEdge()` (hoje não chama) para que
`WindowInsets.ime`/`imePadding` reportem a altura real do teclado ao sheet.

## D3. Capa como imagem autônoma (relaxa a invariante página-real)

**Modelo antigo (edit-import-metadata).** A capa era uma **referência viva**
`WorkCover(chapterId, entryName)` a uma página; a `cover.webp` era **cache regenerável** dessa
página. Uma imagem externa não é página de capítulo nenhum → quebraria essa invariante.

**Modelo novo (direção do dono do formato).** A capa é uma **imagem separada, autônoma**, no nível
da obra — por padrão **extraída** de um capítulo (1ª página), mas **sem dependência ou ligação** com
ele. Consequência: "de capítulo" e "externa" deixam de ser casos distintos — são apenas **fontes**
para o mesmo slot. Some o caso especial; some o `sealed type` que eu cogitei.

```
commit():
  bytesDeCapa = quando a fonte for
                  · página de capítulo → os bytes daquela página (default)
                  · imagem externa     → os bytes do arquivo escolhido no picker
  cover.webp  = encodeThumbnail(bytesDeCapa, 512)   // sempre gravada, durável
  work.json.cover = proveniência OPCIONAL (de onde veio) — sem dependência viva
```

- `cover.webp` (512px) já serve grade **e** detalhe hoje → a capa externa entra no **mesmo**
  encode; **não** guardamos original em alta (não-objetivo). Uma imagem só, coerente com as demais.
- `WorkCover` acomoda as duas proveniências (página `{chapterId, entryName}` | externa). Vira
  metadado de origem, não link vivo: **apagar um capítulo não afeta a capa**.
- **Regenerabilidade.** Para capa de página, `cover.webp` ainda *poderia* ser regenerada da página;
  para capa externa, **não** (a `cover.webp` é a fonte). Como o formato do arquivo já é "não
  contratual/derivado por conteúdo" (o Coil decodifica por bytes, não por extensão), tratamos a
  `cover.webp` como **arquivo de capa durável** — sempre presente, auto-descritivo — e abandonamos a
  premissa "sempre regenerável de uma página".

## D4. Retenção da imagem externa entre Reviewing e commit

O fluxo é 2 fases: `prepare` (nada gravado) → usuário edita → `commit`. A imagem externa escolhida
precisa sobreviver a essa janela, como o `sourceTemp` já faz para a origem. Opção adotada: **reter
os bytes/temp** e carregá-los em `ImportEdits` como a fonte de capa.

```
ImportEdits.cover : CoverChoice
   ├─ CoverChoice.Page(chapterId, entryName)    // escolha na galeria (default)
   └─ CoverChoice.External(handle dos bytes retidos)   // escolha via picker
```

- **Picker**: FileKit (`filekit-dialogs-compose`, já é dependência), modo **imagem única**.
- **Preview**: os bytes externos geram uma thumbnail para a célula selecionada na galeria (mesmo
  `encodeThumbnail`), destacada como as demais.
- **Descarte**: se o usuário trocar de capa ou cancelar, o temp externo é limpo junto com o
  `sourceTemp` (mesma disciplina de `cancel`/`cleanupOrphanTemps`).

## Alternativas descartadas

- **Manter `Dialog` no mobile e forçar `decorFitsSystemWindows=false` na janela do dialog** para
  fazer `imePadding` funcionar: mais frágil e manual que adotar `ModalBottomSheet`, que resolve IME
  nativamente. (Desktop fica com `Dialog` porque bottom sheet no desktop é estranho.)
- **`WorkCover` como `sealed(Page|External)` mantendo a capa como referência viva**: rejeitado pela
  direção do formato — a capa é autônoma, então não há referência viva a modelar; proveniência
  opcional basta.
- **Guardar a imagem externa em alta + `cover.webp` como cache derivado dela**: custo extra sem
  ganho no marco (a capa é 512px em todo lugar). Non-goal.
