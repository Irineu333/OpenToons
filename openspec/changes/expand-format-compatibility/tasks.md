## 1. De-risco (spikes antes de comprometer)

- [ ] 1.1 Spike cinterop `unarr` no iOS/Native (risco nº 1): buildar static lib para
      `iosArm64`+`iosSimulatorArm64`, escrever o `.def`, extrair um `.cbr` RAR4 real no
      simulador/device; definir fallback (recusar RAR no iOS) se não fechar
      > BLOQUEADO (ambiente): exige toolchain C de cross-compile p/ iOS + `unarr` + simulador/
      > device. Fallback já em vigor no código (`RarArchive.ios.kt` recusa RAR — design D5).
- [ ] 1.2 Verificar licença LGPLv3 do `unarr` com link estático; se pesar, avaliar `libunrar`
      > BLOQUEADO junto do 1.1 (faz parte da decisão de integrar `unarr`).
- [x] 1.3 Spike escritor OPZ (ZIP STORED pura-Kotlin sobre Okio): escrever `.opz` e reabrir
      com `openZip` (roundtrip list+read) nos três alvos
      > Roundtrip provado no host (`OpzRoundtripJvmTest`); código compila em JVM, Android e
      > iOS/Native (Kotlin/Native) — E2E on-device fica nos itens 6.2/6.5.

## 2. Formato OPZ e storage por capítulo

- [x] 2.1 Definir o `manifest.json` do OPZ (ordem/nomes de páginas, `detectedLayout`,
      `direction`, dims por página; `obra_id`/`chave_publicador` previstos e nulos) — D7
- [x] 2.2 Implementar o escritor OPZ STORED em `commonMain` (CRC32 puro, `Okio.Sink`) — D6
- [x] 2.3 Implementar leitor do `manifest.json` (kotlinx-serialization-json)
- [x] 2.4 Adotar o layout `obras/{obra}/{capítulo}.opz` no storage próprio — D2
- [x] 2.5 Atualizar `Chapter.archivePath` para o `.opz` do capítulo; aposentar `entryDir`
- [x] 2.6 Recriação destrutiva do schema (sem migração; precedente task 9.14) — D8

## 3. Descompactação RAR (Caminho A)

- [x] 3.1 Definir `expect object RarArchive` com `extractAll(path)` (modo não-lazy) — D4/D5
- [x] 3.2 `actual` JVM+Android via `junrar` (RAR4)
- [ ] 3.3 `actual` iOS/Native via cinterop `unarr` (RAR4)
      > PARCIAL: `actual` iOS presente com o **fallback documentado (D5)** — recusa RAR com
      > mensagem específica da plataforma (não sugere RAR4, que também não roda no iOS). O
      > picker nem oferece CBR/RAR no iOS (task 4.4). Cinterop `unarr` depende do spike 1.1.
- [x] 3.4 Detecção de RAR5 e recusa com mensagem clara (junrar/unarr não cobrem) — D-RAR
- [ ] 3.5 Adicionar `junrar` (JVM/Android) e a config de cinterop `unarr` ao build
      > PARCIAL: `junrar` adicionado (JVM/Android) e compilando. Config de cinterop `unarr`
      > pendente do spike 1.1.

## 4. Pipeline de import unificado

- [x] 4.1 Reader por formato: ZipReader (Okio, CBZ/ZIP) e RarReader (`RarArchive`, CBR/RAR)
- [x] 4.2 Desambiguação unidade vs pacote (entradas = imagens vs `.cbz`/`.cbr`) — D3
      > Regra por presença de imagem (D3): é pacote só quando há arquivos-arquivo E **nenhuma
      > imagem**; havendo imagens é unidade. Corrige o caso real (One Piece v01.cbz) com `.cbz`
      > espúrio aninhado entre as pastas — erro de empacotamento, ignorado. Regressão coberta.
- [x] 4.3 Normalização: origem → capítulos → escritor OPZ → `obras/{obra}/{capítulo}.opz`
      > Escrita OPZ é **streaming** (pico = 1 página) e as páginas de origem são lidas sob
      > demanda — sem bufferizar capítulo/volume. Corrige OOM no Android (heap ~192MB) ao
      > importar volumes grandes (ex.: 284MB). Provado sob heap de 256MB contra o arquivo real.
      > **Desvio consciente do D5** (extract-all → por-entrada no RAR): no JVM/Android o `junrar`
      > extrai por entrada (sem custo de cinterop), mantendo o mesmo teto de memória do ZIP; o
      > iOS (cinterop `unarr`, spike 1.1) segue recusando por ora.
- [x] 4.4 Ampliar o filtro do FileKit para `cbz`, `cbr`, `zip`, `rar`
      > Extensões por plataforma (`ImportFormats`/`rarImportSupported`): CBR/RAR só onde há
      > descompactação RAR. No iOS o picker oferece só CBZ/ZIP — não sugere formatos que
      > sempre falhariam (D5). Dentro da obra: CBZ (+CBR onde suportado).
- [x] 4.5 Heurística de layout por capítulo lida durante a normalização (reusar Marco 1)
- [x] 4.6 UI de progresso do import (RAR + re-zip são mais lentos que o copy-in intacto)

## 5. Fluxos de gestão de capítulos

- [x] 5.1 Importar dentro da obra (CBZ/CBR): anexar capítulos, recusar pacotes (ZIP/RAR)
- [x] 5.2 Seleção múltipla por pressionar-e-segurar na lista de capítulos (detalhe)
- [x] 5.3 Remover capítulos selecionados: apagar `.opz` + progresso, com confirmação
- [x] 5.4 Reindexar `orderIndex`; tratar capa órfã (re-pick) e obra sem capítulos
- [x] 5.5 Ajustar "Remover obra" para apagar a pasta `obras/{obra}/` inteira

## 6. Verificação

- [x] 6.1 Testes: desambiguação unidade/pacote, ordenação, roundtrip OPZ, recusa de RAR5
- [ ] 6.2 E2E nos três alvos: importar CBZ, CBR (RAR4), ZIP e RAR (pacote); ler via OPZ
      > BLOQUEADO (ambiente): exige rodar os apps em device/simulador. Compilação dos três
      > alvos verde; testes de unidade/roundtrip cobrindo a lógica em `jvmTest`.
- [ ] 6.3 E2E: adicionar capítulos a uma obra e remover capítulos por seleção
      > BLOQUEADO (ambiente): fluxo implementado (VM + UI); falta o E2E on-device.
- [x] 6.4 Confirmar que a leitura em regime não toca RAR (só OPZ) e segue 100% offline
- [ ] 6.5 iOS: validar import de CBR RAR4 no device (ou registrar recusa se o spike 1.1 falhar)
      > BLOQUEADO (ambiente): sem device. Recusa registrada no `actual` iOS (fallback D5),
      > coerente com o spike 1.1 pendente.
