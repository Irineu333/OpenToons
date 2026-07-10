## Why

O leitor long strip apresenta um defeito reprodutível em todas as plataformas: ao rolar
para cima, a posição "cai" para o início do capítulo. A causa não é um bug isolado — é
que **a altura do layout é um efeito colateral do decode da imagem**. O `aspectRatio` de
cada página vive num `remember` dentro do escopo do item da `LazyColumn`; quando o item
sai da composição, o estado morre e o item volta a medir uma tela (`fillParentMaxHeight`)
em vez das 5–15 telas reais de uma tira de webtoon. Rolando para cima, cada item que
reentra colapsado é atravessado por um único passo de rolagem.

Enquanto a geometria depender do decoder, o leitor continuará gerando defeitos da mesma
família: rolagem lenta no desktop, progresso infiel, bitmaps acima do limite de textura
da GPU, e uso de memória proporcional à largura da janela. Esta mudança inverte a
dependência — geometria vira **dado**, calculado uma vez no open do capítulo — e deriva
todo o resto dela.

## What Changes

- **Geometria como dado**: um leitor de header de imagem em `commonMain` (PNG, JPEG,
  WebP, GIF) resolve as dimensões de todas as páginas no open do capítulo, lendo apenas
  os primeiros bytes de cada entrada `STORED` do `.opz`. O `manifest.json` passa a ser
  um **cache opcional**, não fonte de verdade — hoje ele já diverge entre plataformas
  (`UIImage` aplica a orientação EXIF; `ImageIO` e `BitmapFactory` não) e desserializa
  como `0` em manifestos antigos.
- **Modelo `Page` ganha dimensões**: `Page(index, entryName, width, height)`, alimentado
  pela geometria resolvida. `LocalImportSource.pages()` deixa de descartar as dimensões.
- **Layout puro e testável**: um `LongStripLayout` sem dependência de UI mapeia
  `(List<PageGeometry>, larguraDeConteúdoPx) → List<Tile>`. Cada tile tem **altura fixa
  conhecida antes da composição**. Nenhum item da `LazyColumn` muda de altura depois de
  medido — o salto deixa de ser compensado e passa a ser impossível por construção.
- **Largura de conteúdo por classe de janela**: janela compacta (< 600dp) preenche a
  largura; janelas média/expandida usam uma coluna centralizada com largura máxima. O
  breakpoint é a largura da janela, não o tipo de dispositivo — um celular em paisagem
  cai no ramo da coluna centralizada.
- **Decode por região, sem upscale**: cada tile decodifica apenas a sua faixa da imagem,
  nunca acima do tamanho nativo da página, com cache LRU e prefetch direcional. Elimina
  bitmaps de página inteira (~69 MB a 1080px de largura) e o risco de exceder o limite
  de textura da GPU (4096–16384 px por dimensão), que hoje é atingido silenciosamente
  por qualquer tira alta.
- **BREAKING** — **Progresso independente de layout**: o progresso do long strip passa a
  ser `(pageIndex, fractionWithinPage)` em vez de uma fração da altura total. A fração
  atual muda de significado quando a largura muda: girar a tela ou abrir o mesmo capítulo
  noutro dispositivo desloca a posição de leitura. Requer migração de `ChapterProgress`,
  que hoje usa `pageIndex` **ou** `scrollFraction`, um por modo.
- **Correção de `completed` no long strip**: hoje `fraction >= 0.99f` exige que a última
  página se torne o `firstVisibleItem`. Numa tela alta com última página curta isso nunca
  ocorre e o capítulo fica eternamente não-lido. Com a altura total conhecida, a condição
  vira posição ≥ 99% do total.
- **Seek, teclado e progresso ligados ao long strip**: `ReaderScreen` não passa
  `seekTarget` ao `LongStripReader` — setas e `PageUp`/`PageDown` não fazem nada no modo
  contínuo. O rodapé lê `currentPage`, que só é atualizado pelo modo paginado.
- **Rolagem de mouse no desktop** (spike): o Compose Desktop entrega um delta constante
  em dp por notch, independente do tamanho dos itens — atravessar um capítulo custa
  centenas de notches, e piora conforme a janela cresce. Nem os tiles nem a coluna de
  largura máxima corrigem isso; exige interceptar `PointerEventType.Scroll` e aplicar um
  fator. Trackpad usa o mesmo canal com deltas contínuos e **não** pode ser amplificado,
  então o fator depende de medição prévia.

## Capabilities

### New Capabilities
- `longstrip-rendering`: geometria de página como dado, tiles de altura fixa, decode por
  região com orçamento de memória, e as invariantes de rolagem que tornam saltos e
  colapsos inexpressáveis.

### Modified Capabilities
- `reading-experience`: o progresso do long strip deixa de ser "fração de rolagem" e passa
  a ser posição independente de layout; "leitura com memória limitada" ganha orçamento
  explícito e limite de textura; "input multiplataforma" passa a cobrir rolagem contínua
  e seek no long strip.
- `offline-library`: "continuar leitura e progresso persistido" passa a retomar por
  `(página, fração dentro da página)`, posição estável entre larguras e dispositivos.

## Impact

**Código**
- `ui/reader/LongStripReader.kt` — reescrito sobre tiles de altura fixa.
- `ui/reader/ReaderScreen.kt` — seek/teclado/progresso do long strip.
- `ui/reader/ReaderViewModel.kt` — carga de geometria; novo modelo de progresso.
- `domain/model/Models.kt` — `Page` com dimensões; `ChapterProgress` com posição.
- `data/source/LocalImportSource.kt` — `pages()` deixa de descartar dimensões.
- `data/image/ArchiveImageFetcher.kt` — fetch de região de página.
- `util/ImageSize.kt` — leitor de header em `commonMain`; `readImageSize` passa a ser
  derivado dele (removendo a divergência EXIF entre as três `actual`).
- Novo `expect fun decodeRegion` — `BitmapRegionDecoder` (Android),
  `ImageReadParam.setSourceRegion` (JVM), `CGImageSource` + crop (iOS).

**Dados**
- Migração de schema de `ChapterProgress`. Progresso long strip existente é convertido
  por aproximação (fração de rolagem → página estimada), aceitando imprecisão pontual.
- Nenhuma migração de `.opz`: o manifesto continua legível e vira cache; dimensões `0`
  deixam de importar.

**Fora de escopo**
- Zoom livre no long strip. O renderer já declara "sem virada nem zoom livre"; manter
  assim mantém o cache de tiles unidimensional. Se zoom entrar depois, os tiles precisam
  ser reamostrados por nível e o cache vira bidimensional.
- Modo paginado, que não sofre dos defeitos acima.
