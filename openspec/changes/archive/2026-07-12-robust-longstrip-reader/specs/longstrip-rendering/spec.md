## ADDED Requirements

### Requirement: Geometria de página resolvida antes do render

O sistema SHALL resolver as dimensões em pixels de todas as páginas de um capítulo long
strip **antes de compor o primeiro item**, lendo o cabeçalho de cada imagem. A leitura de
cabeçalho SHALL ser implementada uma única vez, de forma compartilhada entre plataformas,
produzindo resultado idêntico para os mesmos bytes em Android, iOS e Desktop.

O `manifest.json` do `.opz` SHALL ser tratado como cache opcional, nunca como fonte de
verdade de geometria: dimensões ausentes, zeradas ou divergentes SHALL ser ignoradas em
favor do valor lido do cabeçalho.

#### Scenario: Dimensões conhecidas no open
- **WHEN** um capítulo long strip é aberto
- **THEN** o sistema SHALL conhecer largura e altura de cada página antes de renderizar
  qualquer pixel

#### Scenario: Manifesto sem dimensões
- **WHEN** o manifesto de um capítulo traz `width` e `height` iguais a zero
- **THEN** o sistema SHALL resolver as dimensões pelo cabeçalho das imagens e renderizar
  normalmente, sem degradação

#### Scenario: Mesma imagem, plataformas diferentes
- **WHEN** a mesma imagem é lida em Android, iOS e Desktop
- **THEN** a geometria resolvida SHALL ser idêntica nas três plataformas

### Requirement: Altura de item independente do decode

A altura de qualquer item renderizado no long strip SHALL ser função exclusiva da geometria
da página e da largura de conteúdo. A altura SHALL NOT depender do estado de carregamento
ou decodificação da imagem, e SHALL NOT mudar após a primeira medição do item.

#### Scenario: Item reentra na composição
- **WHEN** um item sai da janela de composição e depois reentra
- **THEN** sua altura SHALL ser exatamente a mesma de antes, independentemente de a imagem
  estar decodificada, em cache ou ainda por carregar

#### Scenario: Imagem termina de carregar
- **WHEN** a imagem de um item visível termina de decodificar
- **THEN** nenhum item SHALL mudar de altura e a posição de rolagem SHALL permanecer
  inalterada

### Requirement: Rolagem reversível

A rolagem no long strip SHALL ser reversível: rolar uma distância qualquer numa direção e a
mesma distância na direção oposta SHALL retornar exatamente à posição original, em qualquer
plataforma, tamanho e proporção de tela.

#### Scenario: Rolar para cima após rolar para baixo
- **WHEN** o usuário rola para baixo uma distância N e depois rola para cima a mesma
  distância N
- **THEN** o leitor SHALL exibir exatamente o mesmo conteúdo da posição inicial

#### Scenario: Rolagem para cima em capítulo longo
- **WHEN** o usuário rola para cima num capítulo cujas páginas são muito mais altas que a
  viewport
- **THEN** a rolagem SHALL avançar de forma contínua e proporcional ao gesto, sem saltar
  para o início do capítulo

### Requirement: Altura total conhecida e seek exato

O sistema SHALL conhecer a altura total do capítulo long strip a partir da geometria e da
largura de conteúdo. Comandos de navegação SHALL posicionar a rolagem de forma exata.

#### Scenario: Seek para uma posição
- **WHEN** o usuário solicita ir a uma posição do capítulo pelos controles
- **THEN** o leitor SHALL posicionar a rolagem exatamente nessa posição

#### Scenario: Progresso exibido
- **WHEN** o usuário rola um capítulo long strip
- **THEN** o progresso exibido SHALL refletir a posição real sobre a altura total, e SHALL
  alcançar 100% ao chegar ao fim do conteúdo

### Requirement: Tiles de altura fixa

O sistema SHALL fatiar cada página do long strip em segmentos verticais de altura fixa,
calculada a partir da geometria da página. O cálculo de segmentos SHALL ser uma função pura
de `(geometria das páginas, largura de conteúdo)`, verificável sem interface gráfica.

#### Scenario: Página alta é fatiada
- **WHEN** uma página tem altura renderizada muito maior que a viewport
- **THEN** ela SHALL ser dividida em segmentos verticais, cada um com altura conhecida
  antes da composição

#### Scenario: Página curta não é fatiada
- **WHEN** uma página tem altura renderizada pequena
- **THEN** ela SHALL ocupar um único segmento

### Requirement: Orçamento de memória e limite de textura

Nenhum bitmap materializado pelo leitor SHALL exceder o limite de textura da plataforma em
qualquer dimensão. O leitor SHALL manter o total de bitmaps residentes dentro de um
orçamento explícito de memória, descartando os menos usados recentemente. O leitor SHALL
NOT decodificar uma imagem acima do seu tamanho nativo.

#### Scenario: Tira muito alta
- **WHEN** uma página tem altura nativa muito superior ao limite de textura da plataforma
- **THEN** o leitor SHALL exibi-la por segmentos, sem que nenhum bitmap exceda o limite

#### Scenario: Página mais estreita que a coluna de leitura
- **WHEN** a largura nativa de uma página é menor que a largura de conteúdo
- **THEN** o leitor SHALL decodificar no tamanho nativo e deixar a escala para o render,
  sem ampliar o bitmap

#### Scenario: Rolagem longa e contínua
- **WHEN** o usuário rola um capítulo longo de ponta a ponta
- **THEN** o uso de memória SHALL permanecer dentro do orçamento, sem crescer com a
  distância percorrida

### Requirement: Largura de conteúdo por classe de janela

O leitor SHALL escolher a largura da coluna de leitura pela largura da janela, e não pelo
tipo de dispositivo. Em janelas compactas a coluna SHALL preencher a largura disponível; em
janelas maiores SHALL ser limitada a uma largura máxima de leitura e centralizada.

#### Scenario: Celular em retrato
- **WHEN** a janela é compacta
- **THEN** a coluna de leitura SHALL preencher a largura da tela

#### Scenario: Desktop ou tablet
- **WHEN** a janela é média ou expandida
- **THEN** a coluna de leitura SHALL ter largura máxima e ficar centralizada

#### Scenario: Rotação cruza o breakpoint
- **WHEN** o usuário gira o dispositivo e a janela muda de classe
- **THEN** o leitor SHALL recalcular a geometria de render e SHALL preservar a posição de
  leitura do usuário
