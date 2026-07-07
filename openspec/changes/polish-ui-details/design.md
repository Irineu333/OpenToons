## Context

Três telas Compose Multiplatform já funcionais (`LibraryScreen`, `DetailScreen`, `ReaderScreen`)
acumularam pequenas arestas visuais. São ajustes de camada de apresentação, sem impacto em
ViewModels, domínio ou persistência. O único item que sobe a nível de spec é a rolagem unificada
do detalhe (queixa de "claustrofobia"); os demais são estilo puro e vivem aqui e nas tasks.

## Goals / Non-Goals

**Goals:**
- Ripple da capa da biblioteca seguindo o contorno arredondado do card.
- Detalhe sem dividers, rolando como superfície única, com ações fixas.
- Capítulos alinhados borda-a-borda (16.dp) com o cabeçalho.
- Progresso do leitor centralizado entre os botões de capítulo.

**Non-Goals:**
- Redesenho de cores, tipografia ou tema.
- Mudanças em navegação, seleção de capítulos ou lógica de progresso.
- Animações novas além das já existentes na chrome do leitor.

## Decisions

### 1. Ripple arredondado no card da biblioteca — `clip` antes de `clickable`
`WorkCover` hoje aplica `clip(RoundedCornerShape(8.dp))` só na `Box` interna da capa; o
`Modifier.clickable` fica no `Column` externo, sem shape, então o indication desenha um retângulo
de quinas vivas sobre capa + título.

**Decisão:** aplicar `Modifier.clip(RoundedCornerShape(8.dp))` no `Column` **antes** do
`.clickable`, de modo que o ripple respeite o contorno do card inteiro. A ordem dos modifiers
importa — o indication é recortado pelo shape já clipado.

_Alternativa descartada:_ passar `indication`/`interactionSource` custom com shape — mais código
para o mesmo efeito visual.

### 2. Detalhe como `LazyColumn` única, TopBar fixa
Hoje `DetailScreen` é um `Column` fixo onde só a `LazyColumn` de capítulos rola; o card do
cabeçalho consome altura fixa e a lista fica espremida.

**Decisão:** manter fora do scroll apenas a barra de ações (`TopBar`/`SelectionBar`) e o bloco de
busy (`LinearProgressIndicator` + status). O restante — card do cabeçalho, título "Capítulos" e as
linhas — vira conteúdo de uma **única `LazyColumn`**: o cabeçalho como um `item {}` inicial, o
rótulo "Capítulos" como outro `item {}`, e as linhas via `items(chapters)`. Assim tudo rola junto.

_Alternativa descartada:_ `Column` externo com `verticalScroll` + a lista dentro — aninhar rolagem
vertical com `LazyColumn` é anti-padrão no Compose (conflito de scroll / altura infinita). Por isso
o cabeçalho entra como item da própria lazy list.

### 3. Remover os `HorizontalDivider`
Os dois dividers (cabeçalho↔lista e entre linhas) são removidos. A separação entre linhas fica por
conta do espaçamento/estilo; a separação cabeçalho↔lista já é dada pelo card elevado
(`surfaceContainerHigh`). Import de `HorizontalDivider` sai do arquivo.

### 4. Alinhamento borda-a-borda dos capítulos (16.dp)
O texto das linhas deve alinhar em 16.dp com o rótulo "Capítulos" e a margem do card. As
`ChapterRow` já usam `padding(16.dp)`; a decisão é garantir que o padding horizontal do conteúdo
permaneça 16.dp após a migração para a lazy list única (o cabeçalho tem margem horizontal de
16.dp), mantendo a coluna visualmente alinhada. O realce de seleção continua respondendo à linha.

### 5. Progresso centralizado no `ReaderBottomBar`
Hoje o `progressText` fica em cima, à esquerda, e os botões prev/next numa `Row` com
`SpaceBetween` abaixo. No long strip (sem slider) o texto fica órfão no canto.

**Decisão:** mover o `progressText` para o centro da `Row` dos botões: `‹ Capítulo` | progresso |
`Capítulo ›`, usando `Arrangement.SpaceBetween` com o texto no meio (ou `weight` no texto central).
Vale para os dois modos — `n/n` (paginado) e `%` (long strip). O slider do modo paginado, quando
presente, permanece acima da `Row`.

## Risks / Trade-offs

- [Rolagem aninhada mal feita quebra o scroll] → cabeçalho entra como `item {}` da própria
  `LazyColumn`, nunca um `Column(verticalScroll)` envolvendo a lista.
- [Perda de affordance ao esconder ações no scroll] → decisão explícita de manter TopMenu/busy
  fixos fora do scroll.
- [Ripple arredondado depender de ordem de modifier] → documentado: `clip` sempre antes de
  `clickable`; risco baixo e visualmente verificável.
- Todos os itens são visuais e reversíveis; sem migração de dados.
