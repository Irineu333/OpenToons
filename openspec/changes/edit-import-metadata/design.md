## Context

Hoje `ContentImporter.importWork(picked)` faz tudo numa passada só, dentro de um único
`withContext(ioDispatcher)`: abre a origem via `withTempSource`, planeja e materializa OPZ por
capítulo (`buildChapters`), escolhe a capa (1ª página do 1º capítulo), grava `work.json`
(`WorkManifestStore.write`), gera `cover.webp` (`CoverStore.generate`) e insere no banco
(`repository.addWork`). O `chapterId` (uuid) é gerado dentro desse fluxo. Não há ponto de
interação: o usuário só vê o resultado na grade.

Para editar título/descrição/capa **antes** de gravar, o fluxo precisa de um ponto de parada
onde a origem já foi lida o suficiente para propor defaults e candidatas de capa, mas **nada**
foi escrito ainda. O `ContentImporter` já tem a peça-chave: `PlannedPage(opzName, read: () ->
ByteArray)` — um leitor preguiçoso por página sobre a origem — o que permite decodificar
thumbnails de candidatas a capa sem materializar OPZ.

## Goals / Non-Goals

**Goals:**
- Quebrar o import em duas fases (`prepare` → `commit`) com uma etapa de revisão editável no
  meio, mantendo a garantia "cancelar = nada gravado".
- Adicionar `description` como dado intrínseco, ponta a ponta (work.json → entity → domínio →
  detalhe), com reconstrução por rescan.
- Permitir escolher a capa entre as páginas da própria obra, preservando a invariante
  `{chapterId, entryName}` e a `cover.webp` derivada de página real.

**Non-Goals:**
- Capa a partir de **imagem externa** (quebraria a invariante página-real).
- Galeria com **todas** as páginas (v1: 1ª página de cada capítulo).
- Editar metadados **depois** do import na tela de detalhe (só exibir a descrição).
- Editar `direction` no formulário (segue detectada; override é estado, já existente).
- Compressão/transcode das páginas (seguem STORED, bytes crus).

## Decisions

### D1 — Duas fases: `prepare()` sem escrita, `commit()` materializa

`ContentImporter.importWork()` é dividido em:

- `prepare(picked): ImportDraft` — copia a origem para um temp **retido** (não apaga ao sair,
  ao contrário do `withTempSource` atual), abre o container, planeja capítulos **em memória**
  gerando `chapterId` estável por capítulo, e devolve um `ImportDraft` com: `defaultTitle`
  (nome do arquivo), o plano de capítulos (com `chapterId` + `PlannedPage`s), a `cover` default
  (`{chapterId, entryName}` da 1ª página do 1º capítulo) e um handle para a origem retida.
  **Nada é gravado em `obras/{obraId}/` nem no banco.**
- `commit(draft, edits): Work` — materializa OPZ por capítulo a partir da origem retida, grava
  `work.json` com `{title, description, direction, cover}` dos `edits`, gera `cover.webp` da
  página apontada por `edits.cover` (lida do `.opz` já materializado), insere no banco e
  **apaga a origem temporária**.

`cancel(draft)` — apaga a origem temporária retida; nada mais existe.

**Por quê:** mantém "cancelar = nada gravado" literal (nenhum `.opz`/`work.json`/`cover.webp`/
linha de banco escritos antes da confirmação). Reaproveita `PlannedPage.read()` para as
thumbnails da revisão sem materializar.

**Alternativa considerada — materializar OPZ antes e limpar no cancelar:** gravar OPZ na Fase
A (rápido, STORED) daria acesso aleatório barato a todas as páginas para a galeria, mas
"cancelar" viraria "gravou e apagou `obras/{obraId}/`", contrariando a intenção de não escrever
nada. Descartado.

### D2 — Capa candidata decodificada da origem via `PlannedPage.read()`

A galeria de capa (v1) mostra a **1ª página de cada capítulo**. Cada thumbnail é decodificada
sob demanda pelo `read()` da `PlannedPage` correspondente (sobre a origem retida), sem OPZ. A
seleção guarda `{chapterId, entryName}`. Na `commit()`, `CoverStore.generate` lê essa página do
`.opz` recém-materializado do capítulo escolhido (mesma API de hoje, só que a página pode não
ser a primeira).

**Por quê:** preserva a invariante da spec (capa = página real) e evita materializar OPZ antes
da confirmação. v1 limita a candidatas leves (uma por capítulo); "qualquer página" fica como
refinamento futuro.

### D3 — `chapterId` gerado na Fase A e carregado até a B

Hoje o `chapterId` nasce dentro da materialização. Passa a nascer no `prepare()` (no plano) e é
carregado para o `commit()`, para que a referência `{chapterId, entryName}` escolhida na
revisão continue apontando o mesmo capítulo quando o OPZ é gravado.

### D4 — `description` como dado, ponta a ponta

`description` é dado intrínseco (ADR-0003 `obra.meta`), então:
- **fonte de verdade:** novo campo em `WorkManifest` (`work.json`), `String` com default `""`
  (forward-compatible: manifestos antigos sem o campo desserializam para vazio).
- **índice:** nova coluna `description` em `WorkEntity` (recriação destrutiva do schema,
  pré-release — precedente das mudanças anteriores).
- **domínio:** `Work.description`; `Mappers` propaga entity↔domínio.
- **reconstrução:** o rescan lê `description` do `work.json` e a grava no índice (regra "disco
  vence").
- **UI:** `DetailScreen` exibe a descrição quando não vazia; omite a área quando vazia.

### D5 — Estado de revisão na camada de UI

`LibraryUiState` ganha um estado `Reviewing(draft)` (ou equivalente) entre a seleção e o
import. `LibraryViewModel` expõe `prepareImport(file)`, `confirmImport(edits)` e
`cancelImport()`. `LibraryScreen` renderiza o formulário de revisão (campo de título, campo de
descrição multilinha, galeria de capa com seleção) sobre esse estado. O progresso da
materialização (`onProgress`) segue como hoje, agora disparado no `confirmImport`.

## Risks / Trade-offs

- **Origem temporária retida durante a edição** → se o app morre no meio da revisão, sobra um
  temp órfão. Mitigação: `prepare()` grava o temp numa subpasta de import volátil, limpa na
  inicialização (varredura de temps órfãos) além de no `cancel`/`commit`.
- **Duas leituras da origem** (thumbnails na revisão + materialização no commit) → custo de
  I/O/decode extra. Mitigação: v1 decodifica só uma thumbnail por capítulo; a materialização é
  a passada pesada única. Aceitável para a interação ganha.
- **`chapterId` movido para o `prepare()`** → risco de divergência se o plano da Fase A não
  casar com a materialização da Fase B. Mitigação: a Fase B materializa exatamente o plano
  produzido na Fase A (mesma lista, mesmos ids), sem replanejar a origem.
- **Schema destrutivo** (nova coluna `description`) → apaga o banco na atualização. Aceitável
  no pré-release (disco é a fonte de verdade; rescan repovoa), consistente com o precedente.
