## Why

O Marco 1 (offline-reader) entregou o leitor lendo **apenas CBZ/ZIP** via Okio
`openZip`, com CBR/RAR **fora de escopo** (design D5: `openZip` só lê ZIP) e o `.cbz`
**guardado intacto** como um único arquivo por obra (`{uuid}.cbz`, copy-in D4). Duas
lacunas ficaram: (a) o universo real de quadrinhos inclui muito **CBR/RAR**; (b) o
armazenamento monolítico impede operações por capítulo (adicionar/remover) sem
reescrever o arquivo inteiro. Esta mudança expande a compatibilidade de formatos e,
ao fazê-lo, adota um **formato interno único (OPZ)** que prepara a evolução do app
rumo ao modelo content-addressed (manifesto+blocos, ADR-0003/poc-07) do Marco 2.

## What Changes

- **Novos formatos de entrada** numa grade 2×2 — unidade vs pacote, ZIP vs RAR:
  - `CBZ` (unidade, ZIP — já suportado) · `CBR` (unidade, RAR — **novo**)
  - `ZIP` (pacote de CBZ — **novo**) · `RAR` (pacote de CBR — **novo**)
- **OPZ como formato interno único**: todo import **normaliza** o conteúdo para OPZ
  (ZIP com entradas **STORED** + `manifest.json`), reservando **um arquivo por
  capítulo**. Reverte a sub-estratégia "guardar o `.cbz` intacto" (Marco 1 D4) em
  favor de um formato uniforme — melhor relação **tamanho × velocidade de leitura**
  e alinhado ao manifesto do Marco 2.
- **Armazenamento por capítulo**: `obras/{obra}/{capítulo}.opz` no storage próprio,
  no lugar do `{uuid}.cbz` monolítico. Habilita adicionar/remover capítulos como
  operações de arquivo baratas.
- **Descompactação RAR** via `expect/actual` (**Caminho A**): `junrar` (JVM/Android,
  Java puro) cobre **RAR4** no Desktop e Android. **RAR no iOS** e **RAR5** são
  **não-objetivos** — recusados no import com mensagem clara (no iOS o picker nem
  oferece RAR). O RAR vive **só no caminho de import** — a leitura em regime continua
  Okio `openZip` sobre OPZ, intocada e cross-platform.
- **Importar dentro da obra**: adicionar novos capítulos/volumes a uma obra existente
  a partir de arquivos-unidade (**CBZ/CBR**), pela tela de detalhe.
- **Deletar capítulos**: seleção por gesto de pressionar-e-segurar → apagar, com
  limpeza dos `.opz` e do progresso associado.

## Capabilities

### Modified Capabilities

- `content-import`: expande os formatos aceitos (CBR/RAR/pacotes), troca o storage
  intacto por **normalização OPZ por capítulo**, adiciona descompactação RAR (RAR4 no
  Desktop/Android) e o fluxo de **adicionar capítulos** a uma obra existente.
- `offline-library`: adiciona **seleção e remoção de capítulos** (pressionar-e-segurar)
  e ajusta a remoção de obra ao novo layout `obras/{obra}/{capítulo}.opz`.

### New Capabilities

<!-- Nenhuma capability nova: a mudança estende content-import e offline-library. -->

## Impact

- **Modelo/schema**: `Chapter.archivePath` passa a apontar o `.opz` **do próprio
  capítulo** (não mais o `.cbz` da obra) e `entryDir` deixa de ser usado (cada OPZ é
  plano). Recriação **destrutiva** do schema (pré-release, sem migração de dados —
  segue o precedente da task 9.14 do Marco 1).
- **Dependências novas**: `junrar` (JVM/Android); `kotlinx-serialization-json`
  (manifesto OPZ). Escritor OPZ é **pura-Kotlin** (ZIP STORED sobre Okio — sem lib
  nativa de escrita).
- **Fora de escopo (não-objetivos)**:
  - **RAR no iOS** — sem cinterop `unarr`; o iOS suporta CBZ/ZIP e recusa RAR por
    design (o picker não oferece RAR). Elimina o risco nº 1 (cinterop) e a questão de
    licença LGPLv3 do `unarr`.
  - **RAR5** — recusado no import com mensagem clara (`junrar` não cobre RAR5).
  - Compressão/criação de RAR (proibida por licença); transcode de imagens para reduzir
    footprint (alavanca separada); qualquer rede (Marco 2).
- **Referências cruzadas**: Marco 1 design D2/D4/D5 (o que esta mudança revisita),
  ADR-0003 (manifesto/obra_id que o OPZ prenuncia), poc-07 (manifesto+blocos).
