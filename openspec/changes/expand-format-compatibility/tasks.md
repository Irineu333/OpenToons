## 1. De-risco (spikes antes de comprometer)

- [ ] 1.1 Spike cinterop `unarr` no iOS/Native (risco nº 1): buildar static lib para
      `iosArm64`+`iosSimulatorArm64`, escrever o `.def`, extrair um `.cbr` RAR4 real no
      simulador/device; definir fallback (recusar RAR no iOS) se não fechar
- [ ] 1.2 Verificar licença LGPLv3 do `unarr` com link estático; se pesar, avaliar `libunrar`
- [ ] 1.3 Spike escritor OPZ (ZIP STORED pura-Kotlin sobre Okio): escrever `.opz` e reabrir
      com `openZip` (roundtrip list+read) nos três alvos

## 2. Formato OPZ e storage por capítulo

- [ ] 2.1 Definir o `manifest.json` do OPZ (ordem/nomes de páginas, `detectedLayout`,
      `direction`, dims por página; `obra_id`/`chave_publicador` previstos e nulos) — D7
- [ ] 2.2 Implementar o escritor OPZ STORED em `commonMain` (CRC32 puro, `Okio.Sink`) — D6
- [ ] 2.3 Implementar leitor do `manifest.json` (kotlinx-serialization-json)
- [ ] 2.4 Adotar o layout `obras/{obra}/{capítulo}.opz` no storage próprio — D2
- [ ] 2.5 Atualizar `Chapter.archivePath` para o `.opz` do capítulo; aposentar `entryDir`
- [ ] 2.6 Recriação destrutiva do schema (sem migração; precedente task 9.14) — D8

## 3. Descompactação RAR (Caminho A)

- [ ] 3.1 Definir `expect object RarArchive` com `extractAll(path)` (modo não-lazy) — D4/D5
- [ ] 3.2 `actual` JVM+Android via `junrar` (RAR4)
- [ ] 3.3 `actual` iOS/Native via cinterop `unarr` (RAR4)
- [ ] 3.4 Detecção de RAR5 e recusa com mensagem clara (junrar/unarr não cobrem) — D-RAR
- [ ] 3.5 Adicionar `junrar` (JVM/Android) e a config de cinterop `unarr` ao build

## 4. Pipeline de import unificado

- [ ] 4.1 Reader por formato: ZipReader (Okio, CBZ/ZIP) e RarReader (`RarArchive`, CBR/RAR)
- [ ] 4.2 Desambiguação unidade vs pacote (entradas = imagens vs `.cbz`/`.cbr`) — D3
- [ ] 4.3 Normalização: origem → capítulos → escritor OPZ → `obras/{obra}/{capítulo}.opz`
- [ ] 4.4 Ampliar o filtro do FileKit para `cbz`, `cbr`, `zip`, `rar`
- [ ] 4.5 Heurística de layout por capítulo lida durante a normalização (reusar Marco 1)
- [ ] 4.6 UI de progresso do import (RAR + re-zip são mais lentos que o copy-in intacto)

## 5. Fluxos de gestão de capítulos

- [ ] 5.1 Importar dentro da obra (CBZ/CBR): anexar capítulos, recusar pacotes (ZIP/RAR)
- [ ] 5.2 Seleção múltipla por pressionar-e-segurar na lista de capítulos (detalhe)
- [ ] 5.3 Remover capítulos selecionados: apagar `.opz` + progresso, com confirmação
- [ ] 5.4 Reindexar `orderIndex`; tratar capa órfã (re-pick) e obra sem capítulos
- [ ] 5.5 Ajustar "Remover obra" para apagar a pasta `obras/{obra}/` inteira

## 6. Verificação

- [ ] 6.1 Testes: desambiguação unidade/pacote, ordenação, roundtrip OPZ, recusa de RAR5
- [ ] 6.2 E2E nos três alvos: importar CBZ, CBR (RAR4), ZIP e RAR (pacote); ler via OPZ
- [ ] 6.3 E2E: adicionar capítulos a uma obra e remover capítulos por seleção
- [ ] 6.4 Confirmar que a leitura em regime não toca RAR (só OPZ) e segue 100% offline
- [ ] 6.5 iOS: validar import de CBR RAR4 no device (ou registrar recusa se o spike 1.1 falhar)
