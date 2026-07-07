## Context

O `expand-format-compatibility` normalizou tudo para **OPZ por capítulo** e já reservou, no
`OpzManifest`, os campos `obraId`/`chavePublicador` como **previstos e nulos** — o próprio
KDoc chama o OPZ de "a fatia por-capítulo do manifesto assinado do Marco 2, materializada em
disco". O que ficou de fora foi a camada **obra**: em disco só existe o capítulo. Título,
capa, ordem e `direction` vivem apenas no banco, misturando **dado** (intrínseco da obra) com
**estado** (favorito, progresso do usuário).

Esta mudança materializa a camada `obra.meta` do ADR-0003 no Marco 1, sem tocar em rede nem
assinatura, e faz o corte estado × dado que transforma o banco de **dono** em **índice
reconstruível + estado pessoal**.

## Goals / Non-Goals

**Goals:**
- Obra **auto-descritiva em disco**: `work.json` (manifesto) + `cover.webp` (capa) por obra.
- Corte explícito **estado × dado**: dado na fonte de verdade em disco; estado no banco.
- `chapterId` estável interno ao capítulo; nome do `.opz` = título/ordem (cosmético).
- `direction` como propriedade **da obra** (sai do manifesto do capítulo).
- **Reconstruir a biblioteca a partir do disco**, preservando estado pessoal por id.
- Capa como **thumbnail** que também resolve o custo da grade.

**Non-Goals:**
- **Compressão/transcode das páginas** (muito complexo; encoder nativo × 3 + lossy). Páginas
  seguem STORED cru. Espaço fica para dedup por CID no Marco 2.
- **Assinatura / `seq` / `chavePublicador`** — reservados e nulos; Marco 2.
- **`.opz` único por obra (nested)** — capítulo é a unidade endereçável (ADR-0003).
- Migração de bibliotecas — pré-release, recriação destrutiva.

## Decisions

### D1 — Opção A (sidecar), não `.opz` único por obra
`obras/{obraId}/` guarda `work.json` + `cover.webp` **ao lado** dos `.opz` de capítulo, que
permanecem arquivos independentes. **Por quê:** o ADR-0003 endereça **capítulos por CID** — a
obra é um manifesto que *aponta* capítulos, não um blob que os *contém*. Capítulo é a unidade
transportável na rede. Nested (um `.opz` por obra) daria "1 arquivo shareável" mas quebraria o
endereçamento por capítulo e forçaria reescrever o contêiner externo a cada capítulo
adicionado.

```
obras/{obraId}/
├── work.json        ← manifesto de obra (fonte de verdade do DADO da obra)
├── cover.webp       ← thumbnail derivada (cache regenerável)
├── {título}.opz     ← nome = título/ordem;  manifest.json c/ chapterId, SEM direction
└── {título}.opz
```

### D2 — `work.json`: espelho local de `obra.meta` (ADR-0003)
```jsonc
{
  "version": 1,
  "obraId": "uuid",            // estável (ADR-0003)
  "chavePublicador": null,     // reservado — assinatura é Marco 2
  "title": "…",
  "direction": "LTR",          // DETECTADA (dado). override do usuário vive no banco
  "cover": { "chapterId": "uuid", "entryName": "001.jpg" }  // qual página é a capa (dado, fiel)
}
```
Forward-compatible: no Marco 2, `cover`/capítulos passam a referenciar **CID** e
`chavePublicador`/assinatura deixam de ser nulos. A **ordem dos capítulos** é o *natural sort*
dos nomes dos `.opz` (mesma regra do import) — não precisa de lista explícita no `work.json`.

### D3 — `chapterId` interno; nome do `.opz` é cosmético
O título do capítulo **é o nome do arquivo** (`{título}.opz`) — simples, legível, ordenável.
Mas nome é péssima **identidade** (rename perde progresso; colisão). Logo o `manifest.json` do
capítulo ganha um `chapterId` (uuid) estável; o **progresso é casado por `chapterId`**, não por
nome. Renomear muda o título e o estado sobrevive. No Marco 2 esse id converge para o CID.

### D4 — `direction` sobe para a obra
`direction` sai do `OpzManifest` (capítulo) e passa a viver só no `work.json`. **Por quê:** é
intrínseca da obra (mangá = RTL), não do capítulo (ADR-0003 a põe em `obra.meta`). O
`detectedLayout` **permanece** no manifesto do capítulo — a *detecção* de layout mora no
capítulo (páginas variam de formato); a *direção* é da obra.

### D5 — Capa é thumbnail derivada (cache), não conteúdo
Duas coisas distintas:
- **Identidade da capa** = `cover: {chapterId, entryName}` no `work.json` → **dado**, fiel,
  aponta uma página real (vira CID no futuro).
- **`cover.webp`** = imagem reduzida → **cache derivado, regenerável**, NÃO entra no CID das
  páginas (é chrome de UI). Por isso gerar essa thumbnail **não** contradiz o corte de
  compressão: nenhuma **página** é transcodificada.

**(Re)geração:** no import da obra **e a cada capítulo adicionado** (`addChapters`). A página
de capa é a 1ª página na ordem; como `addChapters` **anexa** (ordem maior), a capa costuma ser
estável, mas regenerar é barato (uma imagem) e garante `work.json` + `cover.webp` sempre
coerentes. O encoder de imagem é `expect/actual` por plataforma, **restrito à capa**.

### D6 — Split estado × dado e reconstrução a partir do disco
| Dado (disco = verdade) | Estado (banco) |
|---|---|
| `title` da obra, `direction` detectada | favorito, progresso, lido |
| ordem/título dos capítulos (nomes `.opz`) | `directionOverride`, `layoutOverride` |
| capa (qual página) | `createdAt` (fato local do import) |
| `detectedLayout` (no capítulo) | — |

O banco vira **índice**: um *rescan* de `obras/*/work.json` reconstrói `WorkEntity`/
`ChapterEntity`, **preservando** o estado pessoal por casamento em `obraId`/`chapterId` (uuid
estáveis). `WorkEntity` ganha `directionOverride` (par de `layoutOverride`, que já existe).

## Risks / Trade-offs

- **Encoder nativo para a capa**: reintroduz um `expect/actual` de encode de imagem — mas
  **só para a thumbnail** (uma imagem por obra/adição), não no caminho quente de páginas. Se o
  encode WebP não fechar em algum alvo, cair para PNG/JPEG da plataforma (a capa é cache, o
  formato não é contratual).
- **`work.json` × banco divergirem**: a regra é **disco vence** no rescan; escrever
  `work.json` **antes** do upsert no banco no import evita órfãos.
- **Recriação destrutiva do schema**: aceitável (pré-release, precedente Marco 1).

## Migration
Sem migração de dados — recriação destrutiva do schema (pré-release). Bibliotecas existentes
são reimportadas.

## Open Questions
- Formato exato da thumbnail (WebP vs PNG) por alvo — resolver na implementação da capa.
