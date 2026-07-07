## Why

Hoje a obra **não existe em disco** — só o capítulo existe (`obras/{obra}/{capítulo}.opz`,
plano, com `manifest.json` por capítulo). Título da obra, capa, ordem dos capítulos e
`direction` vivem **apenas no banco** (`WorkEntity`/`ChapterEntity`). Três lacunas:

1. **Sem fonte de verdade da obra**: apague o banco e `obras/` vira um monte de `.opz`
   órfãos — sem título, sem capa, sem saber sequer que formam uma obra.
2. **Codependência do banco**: os dados intrínsecos da obra (dado) estão misturados com o
   estado pessoal do usuário (favorito, progresso) na mesma fonte, sem separação.
3. A capa é um **ponteiro para a 1ª página do 1º capítulo**, então a **grade abre um `.opz`
   por célula** para pintar cada capa (destrincha o ZIP a cada scroll).

Esta mudança torna a obra **auto-descritiva em disco** materializando a camada `obra.meta`
do [ADR-0003](../../../docs/decisions/0003-content-model.md) já no Marco 1: um manifesto de
obra (`work.json`) e uma capa de obra (`cover.webp`), com um **split explícito estado × dado**
— o banco deixa de ser dono e vira **índice reconstruível + estado pessoal**.

## What Changes

- **Manifesto de obra (`work.json`)** em `obras/{obraId}/work.json`: espelho local de
  `obra.meta` (ADR-0003) — `version`, `obraId`, `chavePublicador` (reservado, nulo),
  `title`, `direction` **detectada**, `cover` (`{chapterId, entryName}`). É a **fonte de
  verdade** dos dados da obra.
- **Capa de obra (`cover.webp`)**: thumbnail **derivada** (cache) gerada da página de capa.
  Resolve a grade — a lista lê um arquivo pequeno em vez de destrinchar um `.opz` por célula.
  **Gerada uma vez, no import** (capítulos adicionados são anexados e não trocam a capa).
- **`chapterId` interno**: o `manifest.json` do capítulo passa a carregar um `chapterId`
  (uuid) estável. O **nome do `.opz` vira o título/ordem** do capítulo (cosmético); o id
  interno é a **chave de estado** (progresso sobrevive a rename).
- **`direction` sobe para a obra**: removida do `manifest.json` do capítulo e passa a viver
  só no `work.json` (é intrínseca da obra, ADR-0003).
- **Split estado × dado**: dado (título, direction detectada, ordem/capa) na **fonte de
  verdade em disco**; estado (favorito, progresso, lido, `layoutOverride`,
  `directionOverride`, `createdAt` de import) no **banco**.
- **Rescan/reconstrução**: a biblioteca pode ser reconstruída a partir de
  `obras/*/work.json`, **preservando** o estado pessoal casado por `obraId`/`chapterId`.

## Capabilities

### Modified Capabilities

- `content-import`: o import passa a **escrever `work.json` + `cover.webp`** e a gravar o
  `chapterId` no manifesto do capítulo; `direction` sai do manifesto do capítulo. A capa é
  gerada **uma vez, no import** (adicionar capítulos não a regenera).
- `offline-library`: a grade usa a **capa de obra** (`cover.webp`); adiciona **reconstrução
  da biblioteca a partir do disco** com preservação do estado pessoal.

### New Capabilities

<!-- Nenhuma: estende content-import e offline-library. -->

## Impact

- **Modelo/schema**: `WorkEntity`/`ChapterEntity` deixam de ser fonte de verdade dos dados
  da obra (viram índice). `direction` sai do `OpzManifest`; entra `chapterId`. `WorkEntity`
  ganha `directionOverride` (preferência); `layoutOverride` já existe. Recriação
  **destrutiva** do schema (pré-release, sem migração — precedente task 9.14 do Marco 1).
- **Storage**: novo `obras/{obraId}/work.json` e `obras/{obraId}/cover.webp` por obra.
- **Dependências**: encoder de imagem por plataforma **apenas para a thumbnail da capa**
  (chrome, não conteúdo) — escopo mínimo; nada toca as páginas.
- **Fora de escopo (não-objetivos)**:
  - **Compressão/transcode das páginas** — descartado (muito complexo): reintroduziria
    encoder nativo em 3 plataformas + perda geracional. Páginas seguem **STORED, bytes
    crus** (zero-copy + CID barato preservados). Ganho de espaço fica para o Marco 2 (dedup
    por CID).
  - **Campos assinados** (`chavePublicador`, assinatura, `seq` do ADR-0003) — reservados e
    nulos; assinatura é Marco 2.
  - **Único `.opz` por obra (nested)** — descartado: o **capítulo** é a unidade endereçável
    e transportável na rede (ADR-0003). Fica a Opção A (sidecar).
  - Qualquer rede (Marco 2).
- **Referências cruzadas**: ADR-0003 (`obra.meta`/`obra_id` que o `work.json` materializa);
  Marco 1 D6 (STORED, que esta mudança **preserva**); expand-format-compatibility (que criou
  o OPZ por capítulo e reservou `obraId`/`chavePublicador`).
