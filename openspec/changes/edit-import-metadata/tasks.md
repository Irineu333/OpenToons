## 1. Modelo de dados: `description`

- [x] 1.1 Adicionar `description: String = ""` a `WorkManifest` (work.json), forward-compatible (manifestos antigos → vazio)
- [x] 1.2 Adicionar coluna `description` a `WorkEntity` (recriação destrutiva do schema, sem migração)
- [x] 1.3 Adicionar `description` a `Work` (domínio) e propagar em `Mappers` (entity↔domínio, ambos sentidos)
- [x] 1.4 Propagar `description` do `work.json` para o índice na reconstrução por rescan (regra "disco vence")

## 2. Import em duas fases (`prepare` → `commit`)

- [x] 2.1 Definir `ImportDraft` (defaultTitle, plano de capítulos com `chapterId`+`PlannedPage`s, cover default `{chapterId, entryName}`, handle da origem retida)
- [x] 2.2 Gerar `chapterId` no planejamento (Fase A) e carregá-lo até a materialização (Fase B), garantindo mesma lista/ids
- [x] 2.3 Implementar `prepare(picked): ImportDraft` — copia origem para temp **retido**, abre container, planeja em memória, **sem gravar** OPZ/work.json/cover.webp/banco
- [x] 2.4 Implementar `commit(draft, edits): Work` — materializa OPZ do plano, grava `work.json` com `{title, description, direction, cover}` editados, gera `cover.webp` da página escolhida, insere no banco, apaga o temp
- [x] 2.5 Implementar `cancel(draft)` — apaga a origem temporária retida
- [x] 2.6 Limpeza de temps órfãos de import na inicialização (app morto no meio da revisão)
- [x] 2.7 Expor thumbnail sob demanda por candidata a capa via `PlannedPage.read()` (v1: 1ª página de cada capítulo)

## 3. Camada de UI: estado e ViewModel

- [x] 3.1 `ImportViewModel` próprio (pacote `ui.importer`) com `ImportUiState` = `Hidden | Loading | Reviewing(draft) | Error`
- [x] 3.2 `ImportViewModel.start(file)` → `prepare` e abre a revisão (Fase A)
- [x] 3.3 `ImportViewModel.confirm(edits)` → `commit` com progresso e fecha; sucesso reflete via Room
- [x] 3.4 `ImportViewModel.cancel()` → `cancel(draft)` e fecha (nada gravado); `LibraryViewModel`/`LibraryUiState` voltam ao mínimo

## 4. Modal de revisão

- [x] 4.1 `ImportDialog` (modal com `Dialog`) renderizado a partir do `ImportViewModel`; não dispensável durante o processamento
- [x] 4.2 Campo de **título** (pré-preenchido com o default)
- [x] 4.3 Campo de **descrição** multilinha (opcional, vazio por default)
- [x] 4.4 Galeria de **capa** com candidatas (thumbnails das páginas), seleção guardando `{chapterId, entryName}`, capa default destacada
- [x] 4.5 Ações **Cancelar** (nada gravado) e **Importar** (materializa com os valores editados)
- [x] 4.6 `LibraryScreen` delega o arquivo do seletor a `ImportViewModel.start` e hospeda o `ImportDialog`

## 5. Detalhe exibe descrição

- [x] 5.1 `DetailScreen` renderiza a descrição junto aos metadados quando não vazia; omite a área quando vazia
- [x] 5.2 [ajuste] Separar a descrição das demais infos: descrição em bloco próprio (espaço para texto longo) e direção/qtd. de capítulos como labels (`MetaLabel`) no rodapé, alinhadas à base da capa

## 6. Testes e verificação

- [x] 6.1 Teste: `cancel` não deixa artefato em disco nem no banco (nenhum `.opz`/`work.json`/`cover.webp`/linha)
- [x] 6.2 Teste: `commit` grava `work.json` com `title`/`description`/`cover` editados; `description` vazia quando não informada
- [x] 6.3 Teste: capa escolhida em página não-primeira → `work.json.cover = {chapterId, entryName}` correto e `cover.webp` gerada dessa página
- [x] 6.4 Teste: rescan propaga `description` do `work.json` para o índice; forward-compat (work.json sem `description` → vazio)
- [x] 6.5 `openspec validate edit-import-metadata --strict` passa; build/tests verdes nas plataformas
