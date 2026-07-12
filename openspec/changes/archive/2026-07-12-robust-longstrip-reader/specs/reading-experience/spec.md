## MODIFIED Requirements

### Requirement: Progresso de leitura por modo

O sistema SHALL apresentar e persistir o progresso de leitura conforme o modo: número de
página no paginado; posição independente de layout no long strip, expressa como o par
`(índice da página, fração dentro da página)`. O progresso do long strip SHALL NOT ser
expresso como fração da altura total renderizada, por essa fração mudar de significado
quando a largura de conteúdo muda.

A conclusão de um capítulo long strip SHALL ser determinada pela posição sobre a altura
total conhecida, e SHALL ser alcançável em qualquer tamanho de tela.

#### Scenario: Progresso paginado
- **WHEN** o usuário está na página 12 de 24 em modo paginado
- **THEN** o progresso SHALL ser apresentado como posição de página (ex.: 12/24)

#### Scenario: Progresso long strip
- **WHEN** o usuário rola um capítulo long strip
- **THEN** o progresso SHALL ser apresentado como percentual da posição sobre a altura
  total do capítulo

#### Scenario: Progresso sobrevive à mudança de largura
- **WHEN** o usuário gira a tela ou redimensiona a janela durante a leitura de um long strip
- **THEN** a posição de leitura SHALL ser preservada, exibindo o mesmo conteúdo

#### Scenario: Capítulo concluído em tela alta
- **WHEN** o usuário alcança o fim de um capítulo long strip cuja última página é mais curta
  que a viewport
- **THEN** o capítulo SHALL ser marcado como lido

### Requirement: Leitura com memória limitada

O leitor SHALL renderizar capítulos sem carregar todas as páginas em memória. No long strip,
o leitor SHALL segmentar páginas altas e manter os bitmaps residentes dentro de um orçamento
explícito de memória, descartando os menos usados recentemente. Nenhum bitmap SHALL exceder
o limite de textura da plataforma em qualquer dimensão, nem ser decodificado acima do
tamanho nativo da imagem.

#### Scenario: Long strip com imagens muito altas
- **WHEN** um capítulo long strip contém imagens de altura muito grande
- **THEN** o leitor SHALL exibi-las por segmentos, mantendo o uso de memória dentro do
  orçamento e sem exceder o limite de textura

#### Scenario: Memória estável ao percorrer o capítulo
- **WHEN** o usuário rola um capítulo longo de ponta a ponta
- **THEN** o uso de memória SHALL permanecer estável, sem crescer com a distância percorrida

### Requirement: Input multiplataforma básico

O leitor SHALL mapear entradas de toque (mobile) e de mouse/teclado (desktop) aos mesmos
comandos (avançar/voltar página, alternar controles, zoom). No long strip, os comandos de
navegação SHALL deslocar a rolagem, e a rolagem por roda de mouse no desktop SHALL percorrer
o conteúdo em velocidade natural, sem prejudicar dispositivos de rolagem contínua como
trackpads.

#### Scenario: Avançar página no mobile
- **WHEN** o usuário toca na zona lateral de avanço em modo paginado
- **THEN** o leitor SHALL avançar para a próxima página, respeitando a direção da obra

#### Scenario: Avançar página no desktop
- **WHEN** o usuário usa seta/click de borda em modo paginado no desktop
- **THEN** o leitor SHALL executar o mesmo comando de avançar página

#### Scenario: Navegação por teclado no long strip
- **WHEN** o usuário aciona as teclas de navegação num capítulo long strip no desktop
- **THEN** o leitor SHALL deslocar a rolagem, em vez de ignorar o comando

#### Scenario: Roda de mouse no long strip
- **WHEN** o usuário rola um capítulo long strip com a roda do mouse no desktop
- **THEN** cada giro SHALL percorrer uma distância perceptível e proporcional, permitindo
  atravessar o capítulo sem esforço desproporcional

#### Scenario: Trackpad não é amplificado
- **WHEN** o usuário rola com trackpad ou outro dispositivo de rolagem contínua
- **THEN** a rolagem SHALL responder ao gesto de forma natural, sem amplificação
