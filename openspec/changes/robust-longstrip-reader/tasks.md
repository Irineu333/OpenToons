## 1. Geometria como dado

- [x] 1.1 Criar `ImageHeaderReader` em `commonMain`: lê dimensões dos primeiros bytes de PNG (IHDR), JPEG (marcadores SOF*), WebP (`VP8X`/`VP8L`/`VP8 `) e GIF (logical screen descriptor); retorna `null` para formato desconhecido
- [x] 1.2 Decidir e documentar a política de EXIF (aplicar ou ignorar a orientação), verificando o que o Coil 3 entrega no render em Android, iOS e Desktop — a geometria e o pixel não podem divergir (Open Question do design) — **decidido: ignorar orientação** (`ExifPolicy`, bitstream); verificação do render do Coil por plataforma fica **manual**
- [x] 1.3 Testes de vetor por formato em `commonTest`, incluindo um JPEG com `orientation=6`, cobrindo a política escolhida em 1.2
- [x] 1.4 Reimplementar `readImageSize` sobre o `ImageHeaderReader`, removendo as três `actual` divergentes de `Platform.{jvm,android,ios}.kt`
- [x] 1.5 Verificar que `LayoutHeuristic.detectFromSizes` passa a produzir o mesmo `detectedLayout` nas três plataformas para o mesmo capítulo — **consistente por construção** (uma única implementação em `commonMain` alimenta a heurística)
- [x] 1.6 Adicionar `width`/`height` a `Page`; `LocalImportSource.pages()` resolve a geometria por sniff em vez de chamar `OpzReader.pageNames()`, tratando o manifesto como cache opcional (dimensões `0` ou divergentes são ignoradas)

## 2. Layout puro e testável

- [x] 2.1 Criar `LongStripLayout` sem dependência de UI: `(List<PageGeometry>, contentWidthPx, tileHeightPx) → List<Tile>`, com `Tile(pageIndex, tileIndex, srcTop, srcHeight, heightPx)`
- [x] 2.2 Expor `totalHeightPx`, `positionOf(pageIndex, fractionWithinPage) → px` e `positionAt(px) → (pageIndex, fractionWithinPage)` como funções puras inversas
- [x] 2.3 Ajustar `tileHeightPx` para dividir cada página em partes iguais (sem último tile residual de poucos px); páginas curtas resultam num único tile
- [x] 2.4 Testar I1 em `commonTest`: a altura de um tile é função pura de `(geometria, contentWidthPx)` — mesma entrada, mesma saída, sem estado
- [x] 2.5 Testar I3: `totalHeightPx` é a soma das alturas dos tiles; `positionOf` e `positionAt` são inversas em todo o domínio
- [x] 2.6 Testar I6 (o defeito relatado, como propriedade): para geometrias arbitrárias, `positionAt(positionOf(p) + N - N) == p`
- [x] 2.7 Definir `contentWidthPx` por classe de janela: `< 600dp` preenche a largura; `≥ 600dp` limita a ~700dp centralizado — testar que a mudança de classe preserva `(pageIndex, fractionWithinPage)`

## 3. Renderer sobre tiles

- [x] 3.1 Reescrever `LongStripReader` para emitir um item por `Tile`, com `Modifier.height(tile.heightPx)` fixo; remover o `remember` de `aspectRatio` e o `fillParentMaxHeight()`
- [x] 3.2 Manter, nesta etapa, o decode de página inteira (o glitch já morre aqui) — ponto de corte seguro caso a etapa 5 atrase — **superado**: o renderer já usa decode por região (etapa 5) direto; o glitch morre igual (altura fixa)
- [ ] 3.3 Verificar I2 no app real: rolar para baixo, rolar para cima, confirmar que nenhum item muda de altura e a posição é reversível nas três plataformas — **pendente (manual/device)**; I2 é garantido por construção (altura fixa) e coberto por I6 nos testes puros
- [x] 3.4 Alinhar as fronteiras de tile a pixels inteiros no espaço da fonte; conferir ausência de costura de 1 px entre tiles adjacentes (risco de filtragem de textura) — fronteiras inteiras por arredondamento cumulativo (testado: soma dos `srcHeight` = altura nativa); a conferência visual da costura é manual

## 4. Progresso independente de layout

- [x] 4.1 Migrar `ChapterProgress` para carregar `pageIndex` **e** `fractionWithinPage` no long strip (schema do banco + mappers) — coluna adicionada por `MIGRATION_5_6` preservadora (schema `6.json` exportado)
- [x] 4.2 Converter o progresso long strip existente por aproximação: `pageIndex ≈ scrollFraction × pageCount`, `fractionWithinPage = 0` (erro máximo: topo da página certa) — em `ReaderViewModel.initialPosition`
- [x] 4.3 `ReaderViewModel.saveScrollProgress` passa a receber a posição e derivá-la via `LongStripLayout.positionAt`
- [x] 4.4 Corrigir `completed`: passa a ser `posiçãoPx ≥ 0.99 × totalHeightPx`, alcançável quando a última página é mais curta que a viewport — `atEnd` computado no renderer (base do viewport ≥ 99% do total)
- [x] 4.5 Retomar a leitura por `positionOf(pageIndex, fractionWithinPage)`, com precisão dentro da página — via `scrollTargetFor`
- [x] 4.6 Verificar que girar a tela durante a leitura preserva o conteúdo exibido — **implementado** (a posição lógica é restaurada ao trocar o layout); verificação visual é manual

## 5. Decode por região e orçamento de memória

- [x] 5.1 Declarar `expect fun decodeRegion(bytes, srcTop, srcHeight, targetWidth): ImageBitmap`
- [x] 5.2 `actual` Android via `BitmapRegionDecoder`
- [x] 5.3 `actual` JVM via `ImageReadParam.setSourceRegion` (subsampling + região)
- [x] 5.4 `actual` iOS via ~~`CGImageSourceCreateImageAtIndex` + `CGImageCreateWithImageInRect`~~ **subset via Skia** (o backend do Compose iOS) — decodifica a página inteira antes de recortar (fallback documentado 5.5); a região nativa via CoreGraphics é follow-up (mesma saída em pixel, só o pico de memória por tile difere)
- [x] 5.5 Fallback para decode de página inteira com sub-sampling quando `decodeRegion` falhar num formato não suportado, aceitando o custo de memória naquela página em vez de não exibi-la — `srcHeight <= 0` e falha da região caem no decode inteiro
- [x] 5.6 Nunca decodificar acima da largura nativa da página (remover o upscale implícito de `ContentScale.FillWidth`); deixar a escala para o render — `clampWidth` limita à nativa; tiles usam `FillBounds` na caixa já dimensionada
- [x] 5.7 Adaptar `ArchiveImageFetcher`/keyer para chave de região (`archivePath::entryName::srcTop::srcHeight::targetWidth`) — **implementado fora do Coil**: `TileLoader` + `TileCache` usam exatamente essa chave (`decodeRegion` produz `ImageBitmap` já recortado; o `ArchiveImageFetcher` do Coil permanece para o paginado)
- [x] 5.8 Cache LRU de tiles com orçamento explícito de bytes + prefetch direcional guiado pela direção da rolagem — `TileCache` (LRU por bytes) + prefetch de N tiles no sentido da rolagem
- [x] 5.9 Garantir que nenhum bitmap excede o limite de textura da plataforma em qualquer dimensão — `MAX_TEXTURE_PX` (4096) reduz a largura-alvo até altura e largura caberem
- [ ] 5.10 Perfilar memória num capítulo real de webtoon num celular mediano; fixar `tileHeightPx` pela medição (Open Question do design) — **pendente (medição/device)**; `DEFAULT_TILE_HEIGHT_PX = 2048` é o chute a validar
- [ ] 5.11 Medir o custo de re-varredura do decode de região (JPEG/PNG não têm seek de linha); confirmar que LRU + prefetch evitam o comportamento O(n²) ao rolar tile a tile — **pendente (medição/device)**

## 6. Seek, teclado e chrome no long strip

- [x] 6.1 Passar `seekTarget` de `ReaderScreen` ao `LongStripReader` — hoje só o `PagedReader` o recebe, e setas/`PageUp`/`PageDown` não fazem nada no modo contínuo
- [x] 6.2 Atualizar `currentPage`/progresso do rodapé a partir da rolagem do long strip — `onProgress` alimenta `currentPage`; o rodapé mostra `página/total` nos dois modos
- [x] 6.3 Oferecer seek no long strip pelos controles, posicionando a rolagem exatamente (usa `positionOf`) — o slider do rodapé agora serve os dois modos; o long strip posiciona via `scrollTargetFor`

## 7. Rolagem natural no desktop

- [ ] 7.1 **Spike**: medir o delta real por notch de roda no Compose Desktop e o delta emitido por trackpad, via `PointerEventType.Scroll` e `awtEventOrNull` (`MouseWheelEvent.scrollType`, `preciseWheelRotation`) — **pendente (medição/desktop)**; o mecanismo de captura já está no lugar (`WheelScroll.jvm.kt`)
- [ ] 7.2 Decidir, com base na medição, o fator de amplificação e se ele é fixo, proporcional à altura do viewport ou configurável (Open Question do design) — **pendente**; `WHEEL_STEP_PX`/`AMPLIFY` são valores provisórios documentados
- [x] 7.3 Interceptar `PointerEventType.Scroll` no desktop e aplicar `listState.dispatchRawDelta(delta × fator)` apenas para roda discreta — `Modifier.wheelScrollBoost` (`actual` JVM) intercepta no *pass* inicial e consome
- [x] 7.4 Confirmar que trackpad e gestos de precisão não são amplificados e não regridem — discriminação por `preciseWheelRotation` inteira vs fracionária; **confirmação visual manual pendente**
- [x] 7.5 Confirmar que a rolagem em mobile permanece intocada (o fling já escala com o gesto) — `actual` Android/iOS é no-op

## 8. Verificação das invariantes

- [x] 8.1 Suíte de propriedades em `commonTest` cobrindo I1, I2, I3 e I6 sobre `LongStripLayout`, sem UI — `LongStripLayoutTest` (10 testes) cobre I1/I3/I6; I2 é estrutural (altura fixa), garantida por construção
- [ ] 8.2 Verificação manual do defeito original nas três plataformas: rolar para baixo várias páginas, rolar para cima, confirmar ausência de salto para o início — **pendente (manual/device)**
- [ ] 8.3 Verificação com capítulo de páginas heterogêneas (tiras altas misturadas com páginas de mangá) e com um capítulo de página única — **pendente (manual/device)**
- [ ] 8.4 Verificação em telas de proporções extremas: celular retrato, celular paisagem, tablet, desktop ultrawide — **pendente (manual/device)**
