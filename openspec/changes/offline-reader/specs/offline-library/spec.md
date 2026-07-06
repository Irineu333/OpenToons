## ADDED Requirements

### Requirement: Biblioteca em grid

O sistema SHALL apresentar a biblioteca como um grid de capas das obras importadas,
como tela inicial do app.

#### Scenario: Exibir obras importadas
- **WHEN** o usuário abre o app com obras na biblioteca
- **THEN** o sistema SHALL exibir um grid com a capa de cada obra

### Requirement: Favoritos

O sistema SHALL permitir favoritar e desfavoritar obras, e essa marcação SHALL
persistir entre sessões.

#### Scenario: Favoritar uma obra
- **WHEN** o usuário favorita uma obra
- **THEN** a obra SHALL aparecer marcada como favorita nas próximas aberturas do app

### Requirement: Detalhe da obra com lista de capítulos

O sistema SHALL oferecer uma tela de detalhe da obra com capa, metadados e a lista
de capítulos, a partir da qual o usuário inicia a leitura.

#### Scenario: Abrir capítulo pelo detalhe
- **WHEN** o usuário seleciona um capítulo na tela de detalhe
- **THEN** o sistema SHALL abrir o leitor no capítulo escolhido

### Requirement: Continuar leitura e progresso persistido

O sistema SHALL persistir o progresso de leitura por capítulo e oferecer retomar a
leitura de onde parou.

#### Scenario: Retomar de onde parou
- **WHEN** o usuário reabre um capítulo parcialmente lido
- **THEN** o leitor SHALL retomar na posição registrada (página ou fração de rolagem)

#### Scenario: Marcar capítulo lido
- **WHEN** o usuário conclui um capítulo
- **THEN** o sistema SHALL registrar o capítulo como lido e refletir isso no detalhe da obra

### Requirement: Operação totalmente offline

Toda a biblioteca e a leitura SHALL funcionar sem qualquer dependência de rede.

#### Scenario: Uso sem conectividade
- **WHEN** o dispositivo está sem conexão de rede
- **THEN** o usuário SHALL navegar a biblioteca, abrir obras e ler capítulos normalmente
