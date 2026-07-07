## Why

Depois de a biblioteca, o detalhe e o leitor estarem funcionais, sobraram arestas visuais que
deixam a interface com aparência "dura" e apertada: efeito de clique retangular nas capas,
divisores que poluem, um detalhe onde só a lista de capítulos rola (sensação de claustrofobia)
e um progresso de leitura solto num canto. São ajustes de acabamento que elevam a percepção de
qualidade sem tocar em lógica de domínio.

## What Changes

- **Biblioteca**: o efeito de clique (ripple) da capa passa a seguir o contorno arredondado do
  card inteiro (capa + título), em vez de um retângulo de quinas vivas.
- **Detalhe**: remoção dos `HorizontalDivider` (entre cabeçalho e lista, e entre cada capítulo)
  — a separação já vem do card elevado e do estilo das linhas.
- **Detalhe**: a tela inteira passa a rolar como superfície única (card do cabeçalho + lista de
  capítulos num mesmo scroll), acabando com a janela de rolagem espremida. A barra superior de
  ações (voltar/favoritar/excluir) permanece fixa.
- **Detalhe**: o texto dos capítulos alinha borda-a-borda (16.dp) com o cabeçalho.
- **Leitor**: o texto de progresso (`%` no long strip, `n/n` no paginado) passa a ficar
  centralizado entre os botões de capítulo anterior e próximo.

Nenhuma mudança é **BREAKING**: comportamento, dados e navegação permanecem idênticos.

## Capabilities

### New Capabilities
<!-- Nenhuma capability nova; é polish sobre telas existentes. -->

### Modified Capabilities
- `offline-library`: a tela de detalhe da obra passa a rolar como superfície única (cabeçalho +
  lista de capítulos no mesmo scroll), com as ações principais fixas — refinamento observável de
  UX sobre o requisito "Detalhe da obra com lista de capítulos".

## Impact

- **UI apenas** (`shared/src/commonMain/.../ui/`):
  - `library/LibraryScreen.kt` — clip arredondado no card clicável.
  - `detail/DetailScreen.kt` — remove dividers, unifica em `LazyColumn` única, alinha padding.
  - `reader/ReaderScreen.kt` — reposiciona o progresso no `ReaderBottomBar`.
- Sem mudanças em ViewModels, domínio, persistência ou navegação.
- Sem novas dependências.
