# reading-experience Specification

## Purpose
TBD - created by syncing change offline-reader. Update Purpose after archive.

## Requirements
### Requirement: Dois modos de leitura

O sistema SHALL oferecer dois modos de leitura sobre uma mesma chrome: **paginado**
(uma página por vez) e **long strip** (rolagem vertical contínua).

#### Scenario: Renderizar em modo paginado
- **WHEN** um capítulo está em modo paginado
- **THEN** o leitor exibe uma página por vez, com avanço discreto entre páginas

#### Scenario: Renderizar em modo long strip
- **WHEN** um capítulo está em modo long strip
- **THEN** o leitor exibe as páginas em rolagem vertical contínua, sem virada de página

### Requirement: Detecção de layout por heurística

No import, o sistema SHALL decidir o layout do capítulo por heurística de
aspect-ratio das páginas: quando a mediana da razão altura/largura for alta, o
capítulo SHALL ser marcado como long strip; caso contrário, paginado.

#### Scenario: Capítulo de tiras altas
- **WHEN** as páginas de um capítulo têm mediana de altura/largura elevada
- **THEN** o layout detectado SHALL ser long strip

#### Scenario: Capítulo de páginas normais
- **WHEN** as páginas têm razão de página comum (retrato/spread)
- **THEN** o layout detectado SHALL ser paginado

### Requirement: Override manual de layout com precedência

O sistema SHALL permitir override manual do layout nos níveis de obra e de capítulo.
O layout efetivo SHALL ser resolvido como: override do capítulo, senão override da
obra, senão o layout detectado. A detecção SHALL ser preservada separada do override.

#### Scenario: Override de obra vale para os capítulos
- **WHEN** o usuário define o layout no nível da obra
- **THEN** todos os capítulos sem override próprio SHALL usar esse layout, inclusive
  os importados depois

#### Scenario: Limpar override restaura a detecção
- **WHEN** o usuário remove um override de layout
- **THEN** o sistema SHALL voltar a usar o layout detectado, sem perdê-lo

### Requirement: Direção de leitura por obra

O sistema SHALL permitir configurar a direção de leitura (RTL ou LTR) no nível da
obra, com default LTR. A direção SHALL aplicar-se apenas ao modo paginado.

#### Scenario: Mangá em RTL
- **WHEN** o usuário define a direção da obra como RTL e o capítulo é paginado
- **THEN** o avanço de página SHALL ocorrer da direita para a esquerda

### Requirement: Chrome imersiva com toggle

O leitor SHALL iniciar em estado imersivo, com o conteúdo ocupando a tela e os
controles ocultos. Uma interação central (tap/click no centro) SHALL alternar a
exibição dos controles (barra superior e barra inferior de progresso/navegação).

#### Scenario: Alternar controles
- **WHEN** o usuário toca/clica no centro da tela de leitura
- **THEN** os controles SHALL aparecer se ocultos, e ocultar-se se visíveis

### Requirement: Progresso de leitura por modo

O sistema SHALL apresentar e persistir o progresso de leitura conforme o modo:
número de página no paginado; fração de rolagem no long strip.

#### Scenario: Progresso paginado
- **WHEN** o usuário está na página 12 de 24 em modo paginado
- **THEN** o progresso SHALL ser apresentado como posição de página (ex.: 12/24)

#### Scenario: Progresso long strip
- **WHEN** o usuário rola um capítulo long strip
- **THEN** o progresso SHALL ser apresentado como fração de rolagem

### Requirement: Input multiplataforma básico

O leitor SHALL mapear entradas de toque (mobile) e de mouse/teclado (desktop) aos
mesmos comandos (avançar/voltar página, alternar controles, zoom). Atalhos finos de
teclado ficam fora do escopo deste marco.

#### Scenario: Avançar página no mobile
- **WHEN** o usuário toca na zona lateral de avanço em modo paginado
- **THEN** o leitor SHALL avançar para a próxima página, respeitando a direção da obra

#### Scenario: Avançar página no desktop
- **WHEN** o usuário usa seta/click de borda em modo paginado no desktop
- **THEN** o leitor SHALL executar o mesmo comando de avançar página

### Requirement: Leitura com memória limitada

O leitor SHALL renderizar capítulos sem carregar todas as páginas em memória,
aplicando sub-sampling de imagens grandes para evitar erro de memória.

#### Scenario: Long strip com imagens muito altas
- **WHEN** um capítulo long strip contém imagens de altura muito grande
- **THEN** o leitor SHALL exibi-las via sub-sampling, mantendo o uso de memória limitado
