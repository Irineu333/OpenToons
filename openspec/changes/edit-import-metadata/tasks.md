## 1. Modelo de dados: `description`

- [ ] 1.1 Adicionar `description: String = ""` a `WorkManifest` (work.json), forward-compatible (manifestos antigos → vazio)
- [ ] 1.2 Adicionar coluna `description` a `WorkEntity` (recriação destrutiva do schema, sem migração)
- [ ] 1.3 Adicionar `description` a `Work` (domínio) e propagar em `Mappers` (entity↔domínio, ambos sentidos)
- [ ] 1.4 Propagar `description` do `work.json` para o índice na reconstrução por rescan (regra "disco vence")

## 2. Import em duas fases (`prepare` → `commit`)

- [ ] 2.1 Definir `ImportDraft` (defaultTitle, plano de capítulos com `chapterId`+`PlannedPage`s, cover default `{chapterId, entryName}`, handle da origem retida)
- [ ] 2.2 Gerar `chapterId` no planejamento (Fase A) e carregá-lo até a materialização (Fase B), garantindo mesma lista/ids
- [ ] 2.3 Implementar `prepare(picked): ImportDraft` — copia origem para temp **retido**, abre container, planeja em memória, **sem gravar** OPZ/work.json/cover.webp/banco
- [ ] 2.4 Implementar `commit(draft, edits): Work` — materializa OPZ do plano, grava `work.json` com `{title, description, direction, cover}` editados, gera `cover.webp` da página escolhida, insere no banco, apaga o temp
- [ ] 2.5 Implementar `cancel(draft)` — apaga a origem temporária retida
- [ ] 2.6 Limpeza de temps órfãos de import na inicialização (app morto no meio da revisão)
- [ ] 2.7 Expor thumbnail sob demanda por candidata a capa via `PlannedPage.read()` (v1: 1ª página de cada capítulo)

## 3. Camada de UI: estado e ViewModel

- [ ] 3.1 Adicionar estado `Reviewing(draft)` a `LibraryUiState` entre seleção e import
- [ ] 3.2 `LibraryViewModel`: `prepareImport(file)` → carrega draft e vai para `Reviewing`
- [ ] 3.3 `LibraryViewModel`: `confirmImport(edits)` → `commit` com progresso (`onProgress`) e sucesso via Room
- [ ] 3.4 `LibraryViewModel`: `cancelImport()` → `cancel(draft)` e volta ao estado anterior

## 4. Formulário de revisão

- [ ] 4.1 Tela/painel de revisão em `LibraryScreen` sobre o estado `Reviewing`
- [ ] 4.2 Campo de **título** (pré-preenchido com o default)
- [ ] 4.3 Campo de **descrição** multilinha (opcional, vazio por default)
- [ ] 4.4 Galeria de **capa** com candidatas (thumbnails das páginas), seleção guardando `{chapterId, entryName}`, capa default destacada
- [ ] 4.5 Ações **Cancelar** (nada gravado) e **Importar** (materializa com os valores editados)
- [ ] 4.6 Ligar o `rememberFilePickerLauncher` a `prepareImport` (em vez do `import` direto)

## 5. Detalhe exibe descrição

- [ ] 5.1 `DetailScreen` renderiza a descrição junto aos metadados quando não vazia; omite a área quando vazia

## 6. Testes e verificação

- [ ] 6.1 Teste: `cancel` não deixa artefato em disco nem no banco (nenhum `.opz`/`work.json`/`cover.webp`/linha)
- [ ] 6.2 Teste: `commit` grava `work.json` com `title`/`description`/`cover` editados; `description` vazia quando não informada
- [ ] 6.3 Teste: capa escolhida em página não-primeira → `work.json.cover = {chapterId, entryName}` correto e `cover.webp` gerada dessa página
- [ ] 6.4 Teste: rescan propaga `description` do `work.json` para o índice; forward-compat (work.json sem `description` → vazio)
- [ ] 6.5 `openspec validate edit-import-metadata --strict` passa; build/tests verdes nas plataformas
