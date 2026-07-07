# offline-library Specification

## Purpose
TBD - created by syncing change offline-reader. Update Purpose after archive.

## Requirements
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

### Requirement: Selecionar e remover capítulos

O sistema SHALL permitir selecionar capítulos na tela de detalhe da obra por gesto de
**pressionar-e-segurar** (entrando em modo de seleção múltipla) e removê-los. A remoção
SHALL apagar os arquivos `.opz` correspondentes e o progresso associado, e SHALL ser
confirmada pelo usuário por ser irreversível.

#### Scenario: Entrar em modo de seleção
- **WHEN** o usuário pressiona e segura um capítulo na lista
- **THEN** o sistema SHALL entrar em modo de seleção, permitindo marcar vários capítulos

#### Scenario: Remover capítulos selecionados
- **WHEN** o usuário confirma a remoção dos capítulos selecionados
- **THEN** o sistema SHALL apagar seus `.opz` do storage e o progresso, e atualizar a lista

#### Scenario: Remover todos os capítulos
- **WHEN** o usuário remove o último capítulo restante de uma obra
- **THEN** o sistema SHALL tratar a obra como vazia de forma consistente (remover a obra ou
  mantê-la sem capítulos, conforme confirmação do usuário)

### Requirement: Remover obra da biblioteca

O sistema SHALL permitir remover uma obra da biblioteca. A remoção SHALL apagar também a
pasta própria da obra (`obras/{obra}/` com todos os `.opz` dos capítulos) e o progresso
associado, liberando espaço, e SHALL ser confirmada pelo usuário por ser irreversível.

#### Scenario: Remover obra
- **WHEN** o usuário confirma a remoção de uma obra
- **THEN** o sistema SHALL removê-la da biblioteca e apagar seus arquivos próprios e o progresso

#### Scenario: Espaço liberado
- **WHEN** uma obra é removida
- **THEN** todos os `.opz` da pasta da obra SHALL ser apagados do storage do app
