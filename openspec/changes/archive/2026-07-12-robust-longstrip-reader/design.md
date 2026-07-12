## Context

O `LongStripReader` atual (100 linhas) reserva `fillParentMaxHeight()` para cada página até
o Coil resolver a imagem, e então fixa o `aspectRatio` real. O KDoc do arquivo documenta
essa escolha como defesa contra "a rolagem quebrada no desktop". Ela funciona **na primeira
descida** e falha em todo o resto:

```
  aspectRatio: remember(page.entryName) { mutableStateOf<Float?>(null) }
               └── escopo do item da LazyColumn: morre no descarte
```

Descendo, a `LazyColumn` ancora em `(firstVisibleItemIndex, offset)` e nunca remede o que
ficou acima — o colapso é invisível. Subindo, cada item que reentra vale uma tela em vez de
5–15. O usuário atravessa dez páginas com o scroll de uma e chega ao topo do capítulo em
poucos giros de roda. Não é um salto: é o conteúdo acima dele desabando.

Restrições que moldam o desenho:

- **Compose Multiplataforma** (Android, iOS, Desktop/JVM), Coil 3 com `Fetcher` próprio
  sobre entradas `STORED` de um zip (`.opz`).
- **Limite de textura da GPU**: 4096–16384 px por dimensão. Uma tira renderizada a 1080 px
  de largura chega a ~16000 px de altura. Está no limite ou além dele, hoje, em silêncio.
- **Celular mediano** como alvo de memória: bitmaps de página inteira (~69 MB a 1080 px)
  não cabem no orçamento.
- O `manifest.json` do `.opz` **já grava** `width`/`height` por página, mas
  `LocalImportSource.pages()` chama `OpzReader.pageNames()` e os descarta. E as três
  implementações de `readImageSize` discordam entre si (ver Decisão 1).

## Goals / Non-Goals

**Goals:**
- Rolagem sem saltos, em qualquer direção, em qualquer plataforma, tamanho e proporção.
- Progresso fiel: a mesma posição de leitura ao girar a tela ou trocar de dispositivo.
- Memória contida por orçamento explícito, e nenhum bitmap acima do limite de textura.
- Rolagem de roda de mouse com velocidade natural no desktop.
- Que as três primeiras propriedades sejam **verificáveis sem UI**, em `commonTest`.

**Non-Goals:**
- Zoom livre / pinch no long strip. Mantém o cache de tiles unidimensional (uma escala por
  largura de conteúdo). Se zoom entrar depois, o cache vira bidimensional — é uma mudança
  de fôlego próprio.
- Modo paginado (`PagedReader`), que não sofre destes defeitos.
- Trackpad e gestos de precisão no desktop: não podem regredir, mas não são alvo de ajuste.
- Amplificar rolagem em mobile, onde o fling já escala com o gesto.

## Decisions

### 1. Geometria vem de um sniff de header em `commonMain`, não do manifesto

O `manifest.json` não é confiável como fonte de verdade de geometria:

| | JPEG c/ EXIF orientation=6 | manifesto antigo | `.opz` de terceiro |
|---|---|---|---|
| JVM (`ImageIO`) | 1200×800 (bitstream) | `0×0` (default) | não verificado |
| Android (`inJustDecodeBounds`) | 1200×800 (bitstream) | `0×0` | não verificado |
| iOS (`UIImage.imageWithData`) | 800×1200 (**aplica EXIF**) | `0×0` | não verificado |

Para o mesmo arquivo, iOS grava largura e altura trocadas em relação às outras duas. E
`width: Int = 0` é default de serialização: qualquer manifesto antigo desserializa como
zero, sem erro. Nada valida que o número corresponde ao bitstream — é um zip.

**Decisão**: um `ImageHeaderReader` em `commonMain` lê as dimensões dos primeiros bytes
(PNG: IHDR no offset 16; JPEG: varredura de marcadores SOF*; WebP: chunk `VP8X`/`VP8L`/
`VP8 `; GIF: logical screen descriptor). Uma implementação, resultado idêntico nas três
plataformas. A política de EXIF é decidida **uma vez** e alinhada com o que o decoder de
render entrega.

- *Alternativa considerada*: confiar no manifesto e **reconciliar** quando o decoder real
  discordar, compensando o offset de scroll para os itens acima do viewport. Rejeitada: a
  compensação ancorada é correta mas delicada, e resolveria com maquinaria em runtime um
  problema que o sniff resolve em ~1 KB por página, no open. As entradas são `STORED`:
  ler N headers é um seek e alguns kilobytes, mais barato que a primeira imagem que já
  decodificamos hoje.
- *Alternativa considerada*: migrar os `.opz` para regravar o manifesto. Rejeitada: obriga
  reescrever arquivos do usuário para consertar um bug de leitura, e não cobre `.opz` de
  terceiros.

**Consequência**: o manifesto vira **cache opcional**. Quando as dimensões batem com o
sniff, pulamos nada (o sniff já rodou); o valor do manifesto passa a ser sobretudo a
heurística de layout no import. Nenhuma migração de `.opz`. Dimensões `0` deixam de importar.

`readImageSize` (usado por `OpzWriter` e pela `LayoutHeuristic`) passa a derivar do
`ImageHeaderReader`, o que de quebra torna a **detecção de layout** consistente entre
plataformas — hoje um capítulo importado no iOS pode ser detectado como paginado e o mesmo
no desktop como long strip, se as páginas tiverem EXIF.

### 2. Tiles de altura fixa — o salto vira inexpressável

Um `LongStripLayout` puro, sem UI:

```
  LongStripLayout(pages: List<PageGeometry>, contentWidthPx: Int, tileHeightPx: Int)
      → List<Tile>
        Tile(pageIndex, tileIndex, srcTop, srcHeight, heightPx)
```

Cada `Tile` é um item da `LazyColumn` com `Modifier.height(tile.heightPx)`. **Nenhum item
muda de altura após a primeira composição** — não porque compensamos, mas porque a altura
nunca dependeu de nada assíncrono.

- *Alternativa considerada*: um item por página, com altura fixa vinda da geometria (sem
  tiles). Corrige o glitch e o progresso, mas não o limite de textura nem a memória — o
  bitmap de página inteira continua existindo. É a metade do caminho.
- *Alternativa considerada*: mover o `aspectRatio` para um `mutableStateMapOf` no escopo do
  `LongStripReader`. É o conserto de uma linha. Mata o colapso na reentrada, mas não a
  primeira descida, nem a memória, nem a textura, nem o progresso. Rejeitada como solução,
  útil como leitura do diagnóstico.

`tileHeightPx` sai de um alvo (~2048 px) ajustado para dividir a altura da página em partes
iguais, evitando um último tile de 3 px. Páginas curtas (mangá misturado num long strip)
viram um único tile.

### 3. Largura de conteúdo por classe de janela, não por dispositivo

```
  larguraJanela < 600dp   → fillMaxWidth()
  larguraJanela ≥ 600dp   → widthIn(max = 700dp), centralizado
```

Um celular **em paisagem** cai no segundo ramo: preencher 900dp com uma tira de webtoon é
ilegível de qualquer forma. Rotacionar cruza o breakpoint em runtime, o que invalida toda
altura em px e todos os tiles — daí a Decisão 4.

### 4. Progresso em espaço independente de layout

`scrollFraction` sobre a altura total muda de significado quando a largura muda. Gire o
celular e você não está mais onde estava.

```
  progresso = (pageIndex, fractionWithinPage ∈ [0,1])
```

`fractionWithinPage` é exatamente o `offset` que o código atual joga fora ao computar
`firstVisibleItemIndex / (pages.size - 1)`. Reconstruir px a partir do par é trivial em
qualquer largura, DPI ou tiling. Sobrevive a rotação, a troca de dispositivo e a mudanças
futuras de `tileHeightPx`.

Isso também conserta `completed`: hoje `fraction >= 0.99f` exige que a última página se
torne o `firstVisibleItem` — numa tela alta com última página curta, ela entra pelo rodapé
e nunca chega ao topo, e o capítulo fica eternamente não-lido. Com a altura total conhecida
(Decisão 2), a condição vira `posiçãoPx ≥ 0.99 × alturaTotalPx`.

`ChapterProgress` hoje carrega `pageIndex` **ou** `scrollFraction`, um por modo. Passa a
carregar os dois no long strip.

### 5. Decode por região, nunca acima do nativo

```
  expect fun decodeRegion(bytes, srcTop, srcHeight, targetWidth): ImageBitmap
```

| Plataforma | API |
|---|---|
| Android | `BitmapRegionDecoder` |
| JVM | `ImageReadParam.setSourceRegion` |
| iOS | `CGImageSourceCreateImageAtIndex` + `CGImageCreateWithImageInRect` |

Regras:
- **Nunca decodificar acima da largura nativa da página.** Hoje `ContentScale.FillWidth`
  faz upscale de uma tira de 800 px para 1080 px: 82% mais bytes, zero detalhe novo. Deixe
  a GPU escalar.
- Orçamento de bytes com cache **LRU** de tiles e **prefetch direcional** (a direção da
  rolagem decide qual lado pré-carregar).

```
  página inteira @1080px:  1080 × 16000 × 4B = 69 MB   ✗ excede textura e orçamento
  tile        @1080px:     1080 ×  2048 × 4B = 8.8 MB
  janela de ~5 tiles:                          ~44 MB  ✓
```

- *Alternativa considerada*: decodificar a página inteira com sub-sampling e cortar tiles
  do bitmap resultante. Muito mais simples (uma API, três plataformas) e sem o custo de
  re-varredura. Rejeitada: mantém um bitmap de página inteira em memória, e sub-sampling
  agressivo o bastante para caber no orçamento degrada a leitura de texto — que é o
  conteúdo de um webtoon.

**Risco embutido**: JPEG e PNG não têm seek por linha. Um decoder de região varre desde o
topo da imagem, então rolar tile a tile pode custar O(n²) na página. Mitigado pelo cache
LRU e pelo prefetch; a medir na tarefa de perfilamento.

### 6. Rolagem de roda no desktop é um problema separado — e um spike

Registro explícito, porque é contraintuitivo: **tiles não aceleram a roda**. O Compose
Desktop entrega um delta constante em dp por notch, indiferente ao tamanho dos itens. Vinte
tiles ou uma página, a distância em px é a mesma. A coluna de 700dp (Decisão 3) ajuda de
raspão, encurtando a altura total; não muda o passo.

A alavanca é interceptar `PointerEventType.Scroll` e chamar
`listState.dispatchRawDelta(delta × fator)`. Duas incógnitas impedem escrever o design agora:

1. O delta real por notch (`ScrollConfig` do Compose Desktop é `internal`).
2. Trackpad emite pelo **mesmo canal**, com deltas contínuos de alta frequência.
   Multiplicá-los por 8 torna o leitor inutilizável. A distinção existe no AWT
   (`MouseWheelEvent.scrollType`, `preciseWheelRotation`), alcançável via `awtEventOrNull`.

Por isso a tarefa correspondente é um **spike com medição**, e não uma implementação.

### 7. As invariantes são o contrato

| # | Invariante | Bugs que deixam de existir |
|---|---|---|
| I1 | Altura de um item é função pura de `(geometria, larguraDeConteúdo)` | colapso na reentrada; salto ao carregar |
| I2 | Nenhum item muda de altura após a primeira composição | a queda livre ao rolar para cima |
| I3 | A altura total é conhecida no open | seek exato, scrollbar honesta, `completed` confiável |
| I4 | Nenhum bitmap excede `maxTexture` px nem o orçamento de bytes | OOM; página em branco na GPU |
| I5 | Progresso é `(pageIndex, fractionWithinPage)` | perder o lugar ao girar a tela |
| I6 | Rolar `+N` px e depois `−N` px devolve à posição original | o defeito relatado, como teste |

I1–I3 e I6 são testáveis **sem UI**, porque `LongStripLayout` é um objeto Kotlin puro. I6 é
o defeito original escrito como propriedade verificável, ao lado do `ReaderLogicTest`.

## Risks / Trade-offs

- **Re-varredura O(n²) no decode de região** (JPEG/PNG não têm seek de linha) → cache LRU +
  prefetch direcional; perfilar num capítulo real de webtoon antes de fechar `tileHeightPx`.
- **`decodeRegion` são três implementações reais**, cada uma com seu conjunto de formatos.
  `BitmapRegionDecoder` não cobre todo formato que `BitmapFactory` cobre → fallback para
  decode de página inteira com sub-sampling quando a região falhar, aceitando o custo de
  memória naquela página em vez de não exibi-la.
- **Migração de `ChapterProgress`** é destrutiva para o progresso long strip existente: uma
  `scrollFraction` da altura total não pode ser convertida exatamente em `(página, fração)`
  sem conhecer a geometria → converter por aproximação (`pageIndex ≈ fraction × pageCount`,
  `fractionWithinPage = 0`). Erro máximo: o topo da página certa. Aceitável.
- **Emenda entre tiles**: filtragem de textura pode deixar uma costura de 1 px entre tiles
  adjacentes → alinhar as fronteiras a pixels inteiros no espaço da fonte e usar
  `FilterQuality.None` na dimensão de corte, ou sobrepor 1 px.
- **O sniff de header adiciona latência ao open** proporcional ao número de páginas → é um
  seek + ~1 KB por entrada `STORED`, feito no `ioDispatcher`; se um capítulo com centenas
  de páginas se mostrar lento, cachear o resultado por `chapterId` no banco.
- **A `LayoutHeuristic` muda de comportamento** ao passar a ver dimensões consistentes com
  EXIF → capítulos já importados mantêm seu `detectedLayout` gravado; só novos imports
  mudam. Um capítulo mal detectado continua corrigível por override manual.

## Migration Plan

1. `ImageHeaderReader` + testes de vetor por formato. `readImageSize` passa a derivar dele.
   Nada no leitor muda ainda; a `LayoutHeuristic` já se beneficia.
2. `Page` ganha `width`/`height`; `LocalImportSource.pages()` para de descartá-los.
3. `LongStripLayout` puro + testes das invariantes I1–I3 e I6, sem UI.
4. `LongStripReader` reescrito sobre tiles, ainda com decode de página inteira (o glitch
   morre aqui). Ponto de corte seguro: se `decodeRegion` atrasar, esta etapa já entrega
   rolagem sem saltos e progresso fiel.
5. `decodeRegion` + LRU + prefetch. Perfilamento de memória.
6. Migração de `ChapterProgress` (schema + conversão aproximada).
7. Seek, teclado e progresso do rodapé ligados ao long strip.
8. Spike de rolagem no desktop; implementação conforme a medição.

Rollback: as etapas 1–3 são aditivas e inertes. A etapa 4 é o ponto de não-retorno do
renderer; até ela, reverter é remover código não usado.

## Open Questions

- Qual `tileHeightPx` sobrevive ao perfilamento? 2048 é chute informado pelo limite de
  textura, não medição.
- O `.opz` deve passar a **regravar** o manifesto com as dimensões corretas quando o sniff
  discordar, oportunisticamente? Barato, mas escreve no arquivo do usuário durante leitura.
- Qual a política de EXIF: aplicar a orientação (como iOS) ou ignorá-la (como JVM/Android)?
  Precisa casar com o que Coil entrega no render em cada plataforma, senão a geometria e o
  pixel divergem. Provavelmente exige verificar o comportamento do Coil 3 por plataforma.
- O fator de rolagem no desktop deve ser fixo, proporcional à altura do viewport, ou
  configurável pelo usuário?
