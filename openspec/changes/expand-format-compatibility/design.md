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
- **RAR4 no Desktop e Android** (junrar); RAR5 recusado com clareza (não implementado).
- Manter a leitura em regime **inalterada** (Okio `openZip` sobre OPZ, cross-platform).

**Non-Goals:**
- **RAR no iOS** — sem cinterop `unarr`; o iOS suporta CBZ/ZIP e recusa RAR **por design**
  (o picker não oferece RAR). Elimina o risco nº 1 (cinterop) e a licença LGPLv3 do `unarr`.
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

### D4 — RAR via `expect/actual` (Caminho A), RAR no iOS é não-objetivo
```
  commonMain:  expect RarArchive(path) { entryNames(); read(name); close() }
      ├─ jvmMain     → junrar   (RAR4, Java puro, zero build nativo)
      ├─ androidMain → junrar   (RAR4, Java puro)
      └─ iosMain     → recusa por design (RAR no iOS = não-objetivo)
```
**Por quê:** `junrar` cobre Desktop/Android (RAR4, a maior fatia dos CBR reais) sem build
nativo. **RAR no iOS ficou fora de escopo** — o cinterop `unarr` era o risco nº 1 e uma
obrigação de licença LGPLv3; abrir mão dele mantém o iOS 100% Kotlin-puro (lê OPZ, importa
CBZ/ZIP). O `RarArchive` do iOS recusa RAR com mensagem clara, e o picker nem o oferece.
**Extração por-entrada** (não extract-all): o import escreve OPZ em streaming, então o
`junrar` extrai página a página (pico de memória = 1 página; ver correção de OOM).

### D5 — RAR só no import; extração por-entrada (streaming)
Como tudo vira OPZ, o RAR vive **só no caminho de import** e mantém `CbzArchive`/
`LocalImportSource` (leitura em regime) **Okio-puro e intocados** — o leitor só enxerga OPZ.
A extração é **por-entrada** alimentando o escritor OPZ streaming (pico de memória = 1
página; a intenção original de "extract-all" foi revista ao remover o cinterop iOS, que era
sua única motivação — ver correção de OOM). Consequência: a capacidade RAR **degrada por
plataforma sem quebrar o leitor** — Desktop/Android leem RAR4; **iOS recusa RAR (não-objetivo)**
e RAR5 é recusado em todo lugar.

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
  JVM/Desktop   │  ✓ Okio   │  ✓ junrar (Java)   │  ✗ não-objetivo
  Android       │  ✓ Okio   │  ✓ junrar (Java)   │  ✗ não-objetivo
  iOS/Native    │  ✓ Okio   │  ✗ não-objetivo    │  ✗ não-objetivo
  ──────────────┴───────────┴────────────────────┴──────────────────────────
  ✓ pronto/trivial   ✗ não-objetivo (recusado por design, mensagem clara)
```

### Anotação 1 — RAR5 é não-objetivo
`junrar` **só lê RAR4** e lança exceção em RAR5. Cobrir RAR5 exigiria o core C++ `libunrar`
(RARLAB) via JNI/cinterop — outro patamar de esforço. **Decisão:** RAR5 é **não-objetivo**,
recusado no import com mensagem clara.

### Anotação 2 — RAR no iOS é não-objetivo
Não existe RAR em Kotlin puro; o iOS exigiria cinterop a uma lib C (`unarr`/`libunrar`),
com build de static lib + `.def` e obrigação de licença. **Decisão:** **RAR no iOS ficou
fora de escopo** — o iOS lê OPZ e importa CBZ/ZIP, e recusa RAR por design. Sem cinterop, o
iOS permanece 100% Kotlin-puro e o risco nº 1 desaparece.

### Anotação 3 — o custo evitado era build nativo
JVM+Android com `junrar` = zero build nativo (um jar). O peso estaria no cinterop iOS —
evitado ao tornar RAR-no-iOS não-objetivo.

## Risks / Trade-offs

- **Import mais lento** que o copy-in intacto (decode RAR + re-zip) → **Mitigação:** import
  é one-time, em background, com UI de progresso; STORED evita recompressão; escrita OPZ e
  extração RAR são **streaming por página** (pico de memória = 1 página).
- **Escritor ZIP próprio** pode gerar OPZ que o `openZip` não reabra → **Mitigação:** teste
  de roundtrip (escreve OPZ → `openZip` lista+lê), validado no host e E2E nos três alvos.
- **RAR5 / RAR no iOS recusados** frustram quem tem CBR moderno ou usa iOS → **Mitigação:**
  mensagem clara por caso ("RAR5 não suportado; reempacote como CBZ ou RAR4"; "RAR não
  disponível no iOS, importe CBZ/ZIP") e o picker só oferece o que a plataforma aceita.

## Open Questions

- **RAR no iOS / RAR5**: tornados **não-objetivos** — se a demanda por CBR moderno ou por
  RAR no iOS crescer, reabrir avaliando o tier C++ (`libunrar` via cinterop/JNI).
- **Manifesto OPZ**: incluir sha-256 por página já agora (custo baixo, formato-pronto) ou
  manter deferido? Decidido por ora **deferido** (D7), reavaliar no início do Marco 2.
- **Pacote aninhado**: um ZIP contendo ZIPs contendo CBZs — suportar recursão ou limitar a
  um nível? Proposto **um nível** (pacote → unidades); recursão fica fora de escopo.
