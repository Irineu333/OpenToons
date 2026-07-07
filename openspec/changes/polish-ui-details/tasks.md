## 1. Biblioteca — ripple arredondado

- [ ] 1.1 Em `LibraryScreen.kt`, no `WorkCover`, mover/aplicar `Modifier.clip(RoundedCornerShape(8.dp))` no `Column` antes do `.clickable`, para o ripple seguir o contorno do card
- [ ] 1.2 Verificar que o toque na capa e no título dispara o mesmo clique com realce arredondado

## 2. Detalhe — remover dividers

- [ ] 2.1 Remover o `HorizontalDivider()` entre o card do cabeçalho e o rótulo "Capítulos"
- [ ] 2.2 Remover o `HorizontalDivider()` renderizado abaixo de cada `ChapterRow`
- [ ] 2.3 Remover o import não utilizado de `HorizontalDivider` (se não restar uso no arquivo)

## 3. Detalhe — scroll da tela inteira

- [ ] 3.1 Manter fixos, fora do scroll: `TopBar`/`SelectionBar` e o bloco de busy (`LinearProgressIndicator` + status)
- [ ] 3.2 Converter o restante para uma única `LazyColumn`: card do cabeçalho como `item {}` inicial e rótulo "Capítulos" como `item {}`
- [ ] 3.3 Manter as linhas via `items(chapters, key = { it.id })` dentro da mesma `LazyColumn`
- [ ] 3.4 Confirmar que não há `Column(verticalScroll)` aninhando a lista (evitar conflito de rolagem)
- [ ] 3.5 Validar que cabeçalho + capítulos rolam juntos e as ações permanecem acessíveis

## 4. Detalhe — alinhamento dos capítulos

- [ ] 4.1 Garantir padding horizontal de 16.dp no conteúdo das linhas, alinhando com o rótulo "Capítulos" e a margem do cabeçalho
- [ ] 4.2 Conferir o alinhamento borda-a-borda após a migração para a `LazyColumn` única

## 5. Leitor — progresso centralizado

- [ ] 5.1 Em `ReaderBottomBar`, mover o `progressText` para o centro da `Row` de navegação, entre `‹ Capítulo` e `Capítulo ›`
- [ ] 5.2 Manter o slider (modo paginado) acima da `Row`, quando presente
- [ ] 5.3 Validar centralização nos dois modos: `n/n` (paginado) e `%` (long strip)

## 6. Verificação

- [ ] 6.1 Compilar o módulo shared e revisar as três telas (Android/desktop) sem regressões visuais
- [ ] 6.2 Conferir que nenhum ViewModel, domínio ou navegação foi alterado
