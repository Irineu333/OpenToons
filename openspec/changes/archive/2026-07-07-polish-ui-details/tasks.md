## 1. Biblioteca — ripple arredondado

- [x] 1.1 Em `LibraryScreen.kt`, no `WorkCover`, mover/aplicar `Modifier.clip(RoundedCornerShape(8.dp))` no `Column` antes do `.clickable`, para o ripple seguir o contorno do card
- [x] 1.2 Verificar que o toque na capa e no título dispara o mesmo clique com realce arredondado

## 2. Detalhe — remover dividers

- [x] 2.1 Remover o `HorizontalDivider()` entre o card do cabeçalho e o rótulo "Capítulos"
- [x] 2.2 Remover o `HorizontalDivider()` renderizado abaixo de cada `ChapterRow`
- [x] 2.3 Remover o import não utilizado de `HorizontalDivider` (se não restar uso no arquivo)

## 3. Detalhe — scroll da tela inteira

- [x] 3.1 Manter fixos, fora do scroll: `TopBar`/`SelectionBar` e o bloco de busy (`LinearProgressIndicator` + status)
- [x] 3.2 Converter o restante para uma única `LazyColumn`: card do cabeçalho como `item {}` inicial e rótulo "Capítulos" como `item {}`
- [x] 3.3 Manter as linhas via `items(chapters, key = { it.id })` dentro da mesma `LazyColumn`
- [x] 3.4 Confirmar que não há `Column(verticalScroll)` aninhando a lista (evitar conflito de rolagem)
- [x] 3.5 Validar que cabeçalho + capítulos rolam juntos e as ações permanecem acessíveis

## 4. Detalhe — alinhamento dos capítulos

- [x] 4.1 Garantir padding horizontal de 16.dp no conteúdo das linhas, alinhando com o rótulo "Capítulos" e a margem do cabeçalho
- [x] 4.2 Conferir o alinhamento borda-a-borda após a migração para a `LazyColumn` única

## 5. Leitor — progresso centralizado

- [x] 5.1 Em `ReaderBottomBar`, mover o `progressText` para o centro da `Row` de navegação, entre `‹ Capítulo` e `Capítulo ›`
- [x] 5.2 Manter o slider (modo paginado) acima da `Row`, quando presente
- [x] 5.3 Validar centralização nos dois modos: `n/n` (paginado) e `%` (long strip)

## 6. Verificação

- [x] 6.1 Compilar o módulo shared e revisar as três telas (Android/desktop) sem regressões visuais
- [x] 6.2 Conferir que nenhum ViewModel, domínio ou navegação foi alterado
