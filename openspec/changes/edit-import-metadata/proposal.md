## Why

Hoje o import é **fire-and-forget**: ao escolher o arquivo, o `ContentImporter.importWork()`
deriva tudo sozinho numa passada só — `title` do nome do arquivo, `cover` da 1ª página do 1º
capítulo — e grava OPZ + `work.json` + `cover.webp` + banco antes de o usuário ver qualquer
coisa. Não há chance de corrigir um título ruim, e **não existe descrição** em lugar nenhum
(nem no `work.json`, nem no `WorkEntity`, nem no `Work`, nem na UI). O usuário só descobre o
resultado depois que a obra já está na grade.

## What Changes

- **Etapa de revisão no import** (antes de materializar): após escolher o arquivo, uma tela
  de revisão deixa editar **nome**, **descrição** (campo novo) e **capa** antes de gravar
  qualquer coisa. **Cancelar = nada gravado** (nenhum `.opz`, `work.json`, `cover.webp` ou
  linha de banco).
- **Import em duas fases**: o `importWork()` monolítico vira `prepare(file)` (abre a origem,
  planeja capítulos **em memória** com `chapterId` estável, decodifica thumbnails candidatas a
  capa — sem escrever OPZ) + `commit(draft, edits)` (materializa OPZ + `work.json` +
  `cover.webp` + banco com os valores editados). A origem temporária é **retida** entre as
  fases enquanto o usuário edita.
- **Campo `description`** (novo): dado intrínseco da obra, opcional (default vazio), gravado no
  `work.json` como fonte de verdade e propagado a `WorkEntity` (índice), `Work` (domínio) e à
  `DetailScreen` (que hoje **não exibe descrição**). Reconstrução por rescan propaga a
  descrição do `work.json`.
- **Escolha de capa por página da própria obra**: a galeria oferece as páginas do arquivo
  importado (v1: a 1ª página de cada capítulo). A invariante da spec é **preservada** — a capa
  segue apontando uma página real via `{chapterId, entryName}` e a `cover.webp` segue derivada
  de uma página (nenhuma capa externa, nenhuma página transcodificada).

## Capabilities

### New Capabilities
<!-- Nenhuma: estende content-import e offline-library. -->

### Modified Capabilities

- `content-import`: o import ganha uma **etapa de revisão de metadados** antes de materializar
  (editar título, descrição, capa; cancelar não grava nada); o `work.json` passa a conter
  `description`; a capa pode ser **escolhida entre as páginas da obra** mantendo a referência
  `{chapterId, entryName}`.
- `offline-library`: a tela de detalhe passa a **exibir a descrição** da obra; a reconstrução
  a partir do disco propaga `description` do `work.json` para o índice.

## Impact

- **Import**: `ContentImporter.importWork` dividido em `prepare()` + `commit()`; `chapterId`
  gerado no planejamento (Fase A) e carregado até a materialização (Fase B); origem temporária
  retida durante a edição.
- **Modelo/schema**: `WorkManifest` (`work.json`) ganha `description`; `WorkEntity` ganha
  coluna `description` (recriação **destrutiva** do schema, pré-release, sem migração —
  precedente das mudanças anteriores); `Work` (domínio) e `Mappers` ganham `description`.
- **UI**: nova tela/estado de revisão de import (`LibraryUiState`/`LibraryViewModel`/
  `LibraryScreen`) com formulário (nome, descrição, galeria de capa); `DetailScreen` passa a
  renderizar a descrição.
- **Não-objetivos**: capa a partir de **imagem externa** (mantém a invariante página-real);
  edição de metadados **depois** do import na tela de detalhe; edição de `direction` no
  formulário (segue detectada); galeria com **todas** as páginas (v1 usa a 1ª de cada
  capítulo); compressão/transcode de páginas (seguem STORED, bytes crus).
- **Referências cruzadas**: `add-work-manifest` (criou `work.json`/`cover.webp` e a invariante
  `{chapterId, entryName}` que esta mudança preserva); ADR-0003 (`obra.meta`).
