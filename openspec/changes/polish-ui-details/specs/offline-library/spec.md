## MODIFIED Requirements

### Requirement: Detalhe da obra com lista de capítulos

O sistema SHALL oferecer uma tela de detalhe da obra com capa, metadados e a lista
de capítulos, a partir da qual o usuário inicia a leitura. A tela SHALL rolar como uma
superfície única — cabeçalho (capa/metadados) e lista de capítulos no mesmo scroll — de
modo que o conteúdo aproveite toda a altura disponível, sem uma janela de rolagem espremida.
As ações principais da obra (voltar, favoritar, excluir, adicionar capítulos) SHALL permanecer
acessíveis de forma fixa, independentemente da posição de rolagem.

#### Scenario: Abrir capítulo pelo detalhe
- **WHEN** o usuário seleciona um capítulo na tela de detalhe
- **THEN** o sistema SHALL abrir o leitor no capítulo escolhido

#### Scenario: Rolagem da tela inteira
- **WHEN** o usuário rola a tela de detalhe de uma obra
- **THEN** o cabeçalho da obra e a lista de capítulos SHALL rolar juntos como uma única
  superfície, sem que o cabeçalho fique preso ocupando altura fixa

#### Scenario: Ações fixas durante a rolagem
- **WHEN** o usuário rola a lista de capítulos para baixo
- **THEN** as ações principais da obra (voltar/favoritar/excluir/adicionar) SHALL continuar
  acessíveis de forma fixa
