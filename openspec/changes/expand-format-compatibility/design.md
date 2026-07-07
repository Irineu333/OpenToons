## Context

O Marco 1 fixou três decisões que esta mudança **revisita conscientemente**: CBR/RAR
fora de escopo (D2/D5), `.cbz` guardado intacto (D4) e leitura via Okio `openZip` (D5).
A demanda agora é dupla: **ampliar formatos** (CBR + pacotes ZIP/RAR) e **operar por
capítulo** (adicionar/remover). A tese central é que um **formato interno único (OPZ)**
resolve as duas de uma vez — e ainda materializa, em disco, o precursor do manifesto+blocos
do Marco 2 (ADR-0003/poc-07).

RAR é o rochedo técnico: **não existe lib RAR em Kotlin puro** e **nenhuma lib
(pura-Java ou C) cobre RAR5 de graça**. Isso parte o problema em duas realidades —
RAR4 (o "CBR clássico", maioria dos comics) e RAR5 (default do WinRAR moderno, formato
novo e incompatível).

## Goals / Non-Goals

**Goals:**
- Aceitar CBZ, CBR, ZIP (pacote de CBZ) e RAR (pacote de CBR) na importação de obra.
- Normalizar todo import para **OPZ por capítulo** (`obras/{obra}/{capítulo}.opz`).
- Adicionar capítulos a uma obra existente (CBZ/CBR) e remover capítulos (multi-seleção).
- **RAR4 em todas as plataformas**; RAR5 recusado com clareza (não implementado).
- Manter a leitura em regime **inalterada** (Okio `openZip` sobre OPZ, cross-platform).

**Non-Goals:**
- **RAR5** (ver D-RAR / anotação 1). Compressão/criação de RAR (proibida por licença).
- Transcode de imagens (WebP/downscale) para encolher footprint — alavanca separada.
- Migração de bibliotecas do Marco 1 — pré-release, recriação destrutiva.
- Qualquer rede, manifesto assinado ou hashing por bloco em produção (Marco 2).

## Decisions

### D1 — OPZ como formato interno único (normalize-on-import)
Todo import decodifica a origem (qualquer formato) e **materializa OPZ** no storage
próprio. OPZ = **ZIP com entradas STORED** (sem compressão) + `manifest.json`. **Por quê
STORED:** imagens (jpg/webp/png) já vêm comprimidas — re-DEFLATE ganha ~1% e custa CPU
em *toda* leitura de página; STORED dá leitura zero-copy (seek+read) e é a melhor relação
**tamanho × velocidade**. **Reverte o D4 do Marco 1** ("guardar o `.cbz` intacto"): troca
import instantâneo por **formato uniforme** — barato para CBZ (re-zip STORED ≈ cópia de
bytes), caro só para RAR (descompacta uma vez). **Alternativa descartada:** manter cada
formato intacto e ter leitores por formato em regime — espalharia RAR/cinterop pela
leitura (inclusive iOS), o oposto do que queremos.

### D2 — Armazenamento por capítulo: `obras/{obra}/{capítulo}.opz`
Granularidade de **capítulo**, não de obra. **Por quê:** é o que torna os dois fluxos
novos operações de arquivo baratas —

```
  Marco 1: {uuid}.cbz monolítico        Esta mudança: obras/{w}/cap-N.opz
  deletar capítulo → reescrever o zip    deletar capítulo → apagar 1 arquivo
  adicionar capítulo → reescrever o zip  adicionar capítulo → soltar novo .opz + 1 row
```

Alinha com o design do Marco 1 que já chama o capítulo de "≈ bloco/CID" e com o poc-07
(download por capítulo = manifesto + blocos). `Chapter.archivePath` passa a apontar o
`.opz` do próprio capítulo; `entryDir` deixa de ser usado.

### D3 — Grade de formatos 2×2 + desambiguação de pacote
```
                  compressão ZIP        compressão RAR
                ┌───────────────────┬───────────────────┐
  unidade       │  CBZ (existente)  │  CBR (novo)       │
  (imagens)     ├───────────────────┼───────────────────┤
  pacote        │  ZIP = pack CBZ   │  RAR = pack CBR   │
  (arquivos)    │  (novo)           │  (novo)           │
                └───────────────────┴───────────────────┘
```
`CBZ`/`CBR` (na biblioteca **e** dentro da obra); `ZIP`/`RAR` (**só** na biblioteca — um
pacote já descreve uma obra inteira, não cabe "dentro" de outra). **Desambiguação:** um
container é **pacote** quando suas entradas de topo são arquivos-arquivo (`.cbz`/`.cbr`);
é **unidade** quando as entradas são imagens (comportamento legado do Marco 1). No pacote,
cada arquivo interno vira **um capítulo** (ordenação natural dos nomes).

### D4 — RAR via `expect/actual` (Caminho A)
```
  commonMain:  expect RarArchive.extractAll(path): List<(name, bytes)>
      ├─ jvmMain     → junrar   (RAR4, Java puro, zero build nativo)
      ├─ androidMain → junrar   (RAR4, Java puro)
      └─ iosMain     → cinterop unarr (RAR4; static lib iosArm64+iosSimulatorArm64)
```
**Por quê Caminho A:** melhor custo×benefício. `junrar` cobre JVM/Android sem build
nativo; `unarr` (origem *The Unarchiver*, feito para extrair comics) cobre iOS com **um**
build nativo. Cobre **RAR4 em todas** — a maior fatia dos CBR reais. O `RarArchive` é
desenhado para trocar `unarr`→`libunrar` (RAR5) depois **sem tocar o resto**.

### D5 — RAR só no import, modo "extract-all" (não-lazy)
Como tudo vira OPZ, o RAR **não precisa de leitura aleatória por página**. Basta iterar
entradas e despejar bytes uma vez → alimentar o escritor OPZ. Isso **minimiza a superfície
cinterop** no Native (sem seek por entrada) e mantém `CbzArchive`/`LocalImportSource`
(leitura em regime) **Okio-puro e intocados**. Consequência: a capacidade RAR **pode
degradar por plataforma sem quebrar o leitor** — iOS lê RAR4 e recusa RAR5; o leitor só
enxerga OPZ.

### D6 — Escritor OPZ pura-Kotlin (ZIP STORED sobre Okio)
Okio `openZip` é **read-only**; escrever ZIP no Native não tem lib pronta. Mas um ZIP
**STORED** é trivial de escrever (local header + CRC32 + bytes crus + central directory +
EOCD), sem compressão. Implementamos um **escritor STORED mínimo em `commonMain`** sobre
`Okio.Sink` (CRC32 em Kotlin puro). **Por quê:** remove qualquer dependência nativa de
*escrita*; só o *decode* de RAR precisa de nativo. O OPZ resultante é um ZIP válido que o
`openZip` reabre na leitura. **Alternativa descartada:** cinterop minizip/libzip — peso
nativo desnecessário para um formato tão simples.

### D7 — Manifesto OPZ: mínimo agora, formato-pronto para o Marco 2
`manifest.json` (kotlinx-serialization) por capítulo carrega: ordem/nome das páginas,
`detectedLayout`, `direction`, dims por página (evita re-sniff). Campos do ADR-0003
(`obra_id`, `chave_publicador`) ficam **previstos mas nulos** (como no Marco 1). **Hash
sha-256 por página fica deferido** — mas como as entradas são **STORED** (bytes estáveis),
calcular blocos/CID no Marco 2 é quase de graça. OPZ é, assim, a fatia por-capítulo do
manifesto assinado, materializada em disco.

### D8 — Migração: recriação destrutiva (pré-release)
Sem migração de `{uuid}.cbz`→OPZ. Schema recriado destrutivamente; usuário reimporta.
Segue o precedente explícito da task 9.14 do Marco 1. **Por quê:** não há release anterior
(design D-Migration do Marco 1); migrar dobraria o esforço para zero usuário real.

## Feasibility Matrix (RAR)

```
                │  CBZ/ZIP  │  CBR/RAR4          │  CBR/RAR5
  ──────────────┼───────────┼────────────────────┼──────────────────────────
  JVM/Desktop   │  ✓ Okio   │  ✓ junrar (Java)   │  ⚠ só via nativo (JNI)
  Android       │  ✓ Okio   │  ✓ junrar (Java)   │  ⚠ só via nativo (NDK)
  iOS/Native    │  ✓ Okio   │  ⚠ cinterop unarr  │  ⚠ cinterop libunrar (C++)
  ──────────────┴───────────┴────────────────────┴──────────────────────────
  ✓ pronto/trivial   ⚠ viável com trabalho real   (não há ✗ duro)
```

### Anotação 1 — RAR5 inviável no caminho pura-Kotlin (NÃO implementado)
`junrar` (JVM/Android) e `unarr` (Native) **só leem RAR4**; `junrar` lança exceção em
RAR5. Cobrir RAR5 exigiria subir o core C++ `libunrar` (RARLAB) em **todas** as
plataformas (cinterop no Native, JNI no Android/Desktop) — outro patamar de esforço.
**Decisão:** RAR5 é **recusado no import** com mensagem clara; não implementado neste marco.

### Anotação 2 — iOS/Native não tem opção pura, só cinterop
Não existe RAR em Kotlin. iOS obriga cinterop a lib C: `unarr` (LGPLv3, RAR4, comic-focus)
ou `libunrar` (RARLAB, freeware decompress-only, RAR5). Escolhido `unarr` para RAR4. Exige
build de static lib para `iosArm64`+`iosSimulatorArm64` e um `.def` — o **spike de de-risco**.

### Anotação 3 — o custo real é build nativo, não a API
JVM+Android com `junrar` = zero build nativo (um jar). O peso está no cinterop iOS. Se um
dia subir RAR5, soma-se NDK (Android) e JNI/`7-zip-jbinding` (Desktop).

## Risks / Trade-offs

- **Cinterop `unarr` no iOS** (risco nº 1) → **Mitigação:** spike isolado antes de
  comprometer; fallback = recusar CBR/RAR no iOS temporariamente (leitura de OPZ já
  funciona; import de RAR degrada só nessa plataforma sem quebrar nada — ver D5).
- **Licença LGPLv3 do `unarr`** com link estático (obrigação de relink) → **Mitigação:**
  avaliar no spike; alternativa `libunrar` (licença mais permissiva para nosso uso).
- **Import mais lento** que o copy-in intacto (decode RAR + re-zip) → **Mitigação:** import
  é one-time, em background, com UI de progresso; STORED evita recompressão.
- **Escritor ZIP próprio** pode gerar OPZ que o `openZip` não reabra → **Mitigação:** teste
  de roundtrip (escreve OPZ → `openZip` lista+lê) nos três alvos, no estilo do
  `CbzArchiveJvmTest`.
- **RAR5 recusado** frustra quem tem CBR moderno → **Mitigação:** mensagem clara ("RAR5
  não suportado; reempacote como CBZ ou RAR4") e porta aberta (D4) para `libunrar` depois.

## Open Questions

- **Fatia do RAR5 no corpus real** de CBR — se for alta, antecipa o tier `libunrar`.
- **Manifesto OPZ**: incluir sha-256 por página já agora (custo baixo, formato-pronto) ou
  manter deferido? Decidido por ora **deferido** (D7), reavaliar no início do Marco 2.
- **Pacote aninhado**: um ZIP contendo ZIPs contendo CBZs — suportar recursão ou limitar a
  um nível? Proposto **um nível** (pacote → unidades); recursão fica fora de escopo.
