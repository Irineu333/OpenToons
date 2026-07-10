## 1. Geometria como dado

- [ ] 1.1 Criar `ImageHeaderReader` em `commonMain`: lê dimensões dos primeiros bytes de PNG (IHDR), JPEG (marcadores SOF*), WebP (`VP8X`/`VP8L`/`VP8 `) e GIF (logical screen descriptor); retorna `null` para formato desconhecido
- [ ] 1.2 Decidir e documentar a política de EXIF (aplicar ou ignorar a orientação), verificando o que o Coil 3 entrega no render em Android, iOS e Desktop — a geometria e o pixel não podem divergir (Open Question do design)
- [ ] 1.3 Testes de vetor por formato em `commonTest`, incluindo um JPEG com `orientation=6`, cobrindo a política escolhida em 1.2
- [ ] 1.4 Reimplementar `readImageSize` sobre o `ImageHeaderReader`, removendo as três `actual` divergentes de `Platform.{jvm,android,ios}.kt`
- [ ] 1.5 Verificar que `LayoutHeuristic.detectFromSizes` passa a produzir o mesmo `detectedLayout` nas três plataformas para o mesmo capítulo
- [ ] 1.6 Adicionar `width`/`height` a `Page`; `LocalImportSource.pages()` resolve a geometria por sniff em vez de chamar `OpzReader.pageNames()`, tratando o manifesto como cache opcional (dimensões `0` ou divergentes são ignoradas)

## 2. Layout puro e testável

- [ ] 2.1 Criar `LongStripLayout` sem dependência de UI: `(List<PageGeometry>, contentWidthPx, tileHeightPx) → List<Tile>`, com `Tile(pageIndex, tileIndex, srcTop, srcHeight, heightPx)`
- [ ] 2.2 Expor `totalHeightPx`, `positionOf(pageIndex, fractionWithinPage) → px` e `positionAt(px) → (pageIndex, fractionWithinPage)` como funções puras inversas
- [ ] 2.3 Ajustar `tileHeightPx` para dividir cada página em partes iguais (sem último tile residual de poucos px); páginas curtas resultam num único tile
- [ ] 2.4 Testar I1 em `commonTest`: a altura de um tile é função pura de `(geometria, contentWidthPx)` — mesma entrada, mesma saída, sem estado
- [ ] 2.5 Testar I3: `totalHeightPx` é a soma das alturas dos tiles; `positionOf` e `positionAt` são inversas em todo o domínio
- [ ] 2.6 Testar I6 (o defeito relatado, como propriedade): para geometrias arbitrárias, `positionAt(positionOf(p) + N - N) == p`
- [ ] 2.7 Definir `contentWidthPx` por classe de janela: `< 600dp` preenche a largura; `≥ 600dp` limita a ~700dp centralizado — testar que a mudança de classe preserva `(pageIndex, fractionWithinPage)`

## 3. Renderer sobre tiles

- [ ] 3.1 Reescrever `LongStripReader` para emitir um item por `Tile`, com `Modifier.height(tile.heightPx)` fixo; remover o `remember` de `aspectRatio` e o `fillParentMaxHeight()`
- [ ] 3.2 Manter, nesta etapa, o decode de página inteira (o glitch já morre aqui) — ponto de corte seguro caso a etapa 5 atrase
- [ ] 3.3 Verificar I2 no app real: rolar para baixo, rolar para cima, confirmar que nenhum item muda de altura e a posição é reversível nas três plataformas
- [ ] 3.4 Alinhar as fronteiras de tile a pixels inteiros no espaço da fonte; conferir ausência de costura de 1 px entre tiles adjacentes (risco de filtragem de textura)

## 4. Progresso independente de layout

- [ ] 4.1 Migrar `ChapterProgress` para carregar `pageIndex` **e** `fractionWithinPage` no long strip (schema do banco + mappers)
- [ ] 4.2 Converter o progresso long strip existente por aproximação: `pageIndex ≈ scrollFraction × pageCount`, `fractionWithinPage = 0` (erro máximo: topo da página certa)
- [ ] 4.3 `ReaderViewModel.saveScrollProgress` passa a receber a posição e derivá-la via `LongStripLayout.positionAt`
- [ ] 4.4 Corrigir `completed`: passa a ser `posiçãoPx ≥ 0.99 × totalHeightPx`, alcançável quando a última página é mais curta que a viewport
- [ ] 4.5 Retomar a leitura por `positionOf(pageIndex, fractionWithinPage)`, com precisão dentro da página
- [ ] 4.6 Verificar que girar a tela durante a leitura preserva o conteúdo exibido

## 5. Decode por região e orçamento de memória

- [ ] 5.1 Declarar `expect fun decodeRegion(bytes, srcTop, srcHeight, targetWidth): ImageBitmap`
- [ ] 5.2 `actual` Android via `BitmapRegionDecoder`
- [ ] 5.3 `actual` JVM via `ImageReadParam.setSourceRegion`
- [ ] 5.4 `actual` iOS via `CGImageSourceCreateImageAtIndex` + `CGImageCreateWithImageInRect`
- [ ] 5.5 Fallback para decode de página inteira com sub-sampling quando `decodeRegion` falhar num formato não suportado, aceitando o custo de memória naquela página em vez de não exibi-la
- [ ] 5.6 Nunca decodificar acima da largura nativa da página (remover o upscale implícito de `ContentScale.FillWidth`); deixar a escala para o render
- [ ] 5.7 Adaptar `ArchiveImageFetcher`/keyer para chave de região (`archivePath::entryName::srcTop::srcHeight::targetWidth`)
- [ ] 5.8 Cache LRU de tiles com orçamento explícito de bytes + prefetch direcional guiado pela direção da rolagem
- [ ] 5.9 Garantir que nenhum bitmap excede o limite de textura da plataforma em qualquer dimensão
- [ ] 5.10 Perfilar memória num capítulo real de webtoon num celular mediano; fixar `tileHeightPx` pela medição (Open Question do design)
- [ ] 5.11 Medir o custo de re-varredura do decode de região (JPEG/PNG não têm seek de linha); confirmar que LRU + prefetch evitam o comportamento O(n²) ao rolar tile a tile

## 6. Seek, teclado e chrome no long strip

- [ ] 6.1 Passar `seekTarget` de `ReaderScreen` ao `LongStripReader` — hoje só o `PagedReader` o recebe, e setas/`PageUp`/`PageDown` não fazem nada no modo contínuo
- [ ] 6.2 Atualizar `currentPage`/progresso do rodapé a partir da rolagem do long strip — hoje só o `onPageSettled` do modo paginado o alimenta, e o rodapé fica congelado
- [ ] 6.3 Oferecer seek no long strip pelos controles, posicionando a rolagem exatamente (usa `positionOf`)

## 7. Rolagem natural no desktop

- [ ] 7.1 **Spike**: medir o delta real por notch de roda no Compose Desktop e o delta emitido por trackpad, via `PointerEventType.Scroll` e `awtEventOrNull` (`MouseWheelEvent.scrollType`, `preciseWheelRotation`)
- [ ] 7.2 Decidir, com base na medição, o fator de amplificação e se ele é fixo, proporcional à altura do viewport ou configurável (Open Question do design)
- [ ] 7.3 Interceptar `PointerEventType.Scroll` no desktop e aplicar `listState.dispatchRawDelta(delta × fator)` apenas para roda discreta
- [ ] 7.4 Confirmar que trackpad e gestos de precisão não são amplificados e não regridem
- [ ] 7.5 Confirmar que a rolagem em mobile permanece intocada (o fling já escala com o gesto)

## 8. Verificação das invariantes

- [ ] 8.1 Suíte de propriedades em `commonTest` cobrindo I1, I2, I3 e I6 sobre `LongStripLayout`, sem UI
- [ ] 8.2 Verificação manual do defeito original nas três plataformas: rolar para baixo várias páginas, rolar para cima, confirmar ausência de salto para o início
- [ ] 8.3 Verificação com capítulo de páginas heterogêneas (tiras altas misturadas com páginas de mangá) e com um capítulo de página única
- [ ] 8.4 Verificação em telas de proporções extremas: celular retrato, celular paisagem, tablet, desktop ultrawide
