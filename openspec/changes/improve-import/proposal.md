## Why

Três atritos no import de nova obra, todos na etapa de revisão (`ImportDialog`/`ReviewContent`):

1. **Botão "Importar" some em telas pequenas.** O modal (`Dialog`) não limita a própria
   altura à tela e só o **miolo** rola (`heightIn(max=420).verticalScroll`); a `Row` de ações
   fica **fora** do scroll, depois do miolo. Numa viewport curta (phone pequeno, paisagem) ou
   com o teclado aberto, os botões caem abaixo da dobra e **não há como alcançá-los**.
2. **Não dá para fechar o teclado.** Não há gesto de tocar fora do campo para dispensar o IME,
   nem `ImeAction` nos campos. No iOS **não existe** botão de sistema para baixar o teclado —
   então, uma vez aberto, ele fica preso na frente do formulário.
3. **Alguns CBZ não trazem capa.** Toda candidata automática é a 1ª página de um capítulo; se o
   arquivo só tem páginas de miolo, **nenhuma** candidata serve como capa. Hoje a capa é
   **restrita às páginas da própria obra** (invariante `{chapterId, entryName}`), sem escapatória.

## What Changes

- **Modal de revisão com rodapé sempre visível e ciente do teclado.** O shell passa a
  `ModalBottomSheet` no **mobile** (Android/iOS) e mantém `Dialog` no **desktop** (via
  `expect/actual`). Layout de 3 faixas — header fixo · conteúdo `weight(1).verticalScroll` ·
  **footer fixo** — de modo que as ações nunca são empurradas para fora. No Android,
  `enableEdgeToEdge()` no host dá os insets reais para o sheet subir acima do IME.
- **Fechar o teclado.** Tocar em qualquer área não-campo do modal chama `focusManager.clearFocus()`
  (dispensa o IME nas três plataformas, essencial no iOS); os campos ganham `ImeAction`
  (`Next` → `Done`). Toque **dentro** do modal só baixa o teclado — não fecha o modal.
- **Capa a partir de imagem externa.** A galeria de capas ganha uma célula "+" à direita que
  abre o seletor de imagem (FileKit). Isso relaxa a invariante: a capa deixa de ser uma
  **referência viva** a uma página e passa a ser uma **imagem autônoma** no nível da obra — por
  padrão extraída de um capítulo (1ª página), mas **sem vínculo** com ele. "De capítulo" e
  "externa" são apenas **duas fontes** para o mesmo slot; a `cover.webp` é sempre gravada no
  commit a partir dos bytes escolhidos (`encodeThumbnail`, 512px), venha de onde vier.

## Capabilities

### New Capabilities
<!-- Nenhuma: estende content-import. -->

### Modified Capabilities

- `content-import`:
  - a **revisão de metadados** ganha usabilidade em telas pequenas e com teclado (ações sempre
    alcançáveis; tocar fora do campo baixa o IME);
  - a **escolha de capa** deixa de ser restrita às páginas da obra: aceita **imagem externa**;
  - a **capa de obra** passa a ser uma **imagem autônoma** (sem dependência viva de página),
    sempre materializada no commit a partir da fonte escolhida.

## Impact

- **UI**: `ImportDialog`/`ReviewContent` reestruturado (header/conteúdo/footer); shell
  `expect/actual` (`ImportModalShell` → `ModalBottomSheet` no mobile, `Dialog` no desktop);
  `clearFocus` no toque de fundo + `KeyboardOptions`/`KeyboardActions` nos campos; célula "+"
  de imagem externa na galeria de capas com picker do FileKit.
- **Host Android**: `MainActivity` passa a chamar `enableEdgeToEdge()` para insets de IME reais.
- **Import**: `ContentImporter.commit` passa a materializar a `cover.webp` a partir dos **bytes**
  da fonte escolhida (página **ou** imagem externa), não mais de uma referência de página viva;
  `ImportEdits`/`ImportDraft` carregam a fonte de capa (página ou bytes externos retidos entre
  Reviewing e commit).
- **Modelo**: `WorkManifest.cover` deixa de ser dependência viva e vira **proveniência opcional**
  (de onde a capa veio); `WorkCover` acomoda "página" e "externa". `cover.webp` continua sendo o
  arquivo de capa em disco (auto-descritivo), agora **sempre** durável.
- **Não-objetivos**: guardar a imagem externa em **alta resolução** (a capa segue 512px, como as
  demais); **editar a capa depois** do import (tela de detalhe); trocar a capa de obra já
  existente; migração de `work.json` de obras antigas (pré-release, recriação destrutiva como
  nas mudanças anteriores); tornar o `Dialog` do desktop um bottom sheet.
- **Referências cruzadas**: `edit-import-metadata` (introduziu a revisão e a invariante
  página-real que esta mudança relaxa); `add-work-manifest` (`work.json`/`cover.webp`).
