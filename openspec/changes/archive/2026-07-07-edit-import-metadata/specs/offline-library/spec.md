## MODIFIED Requirements

### Requirement: Detalhe da obra com lista de capítulos

O sistema SHALL oferecer uma tela de detalhe da obra com capa, metadados e a lista
de capítulos, a partir da qual o usuário inicia a leitura. Os metadados exibidos SHALL incluir
o **título** e a **descrição** da obra (quando presente). A tela SHALL rolar como uma
superfície única — cabeçalho (capa/metadados) e lista de capítulos no mesmo scroll — de
modo que o conteúdo aproveite toda a altura disponível, sem uma janela de rolagem espremida.
As ações principais da obra (voltar, favoritar, excluir, adicionar capítulos) SHALL permanecer
acessíveis de forma fixa, independentemente da posição de rolagem.

#### Scenario: Abrir capítulo pelo detalhe
- **WHEN** o usuário seleciona um capítulo na tela de detalhe
- **THEN** o sistema SHALL abrir o leitor no capítulo escolhido

#### Scenario: Exibir descrição da obra
- **WHEN** a obra possui descrição não vazia
- **THEN** a tela de detalhe SHALL exibir a descrição junto aos metadados da obra

#### Scenario: Descrição ausente
- **WHEN** a obra não possui descrição (texto vazio)
- **THEN** a tela de detalhe SHALL omitir a área de descrição, sem espaço vazio

#### Scenario: Rolagem da tela inteira
- **WHEN** o usuário rola a tela de detalhe de uma obra
- **THEN** o cabeçalho da obra e a lista de capítulos SHALL rolar juntos como uma única
  superfície, sem que o cabeçalho fique preso ocupando altura fixa

#### Scenario: Ações fixas durante a rolagem
- **WHEN** o usuário rola a lista de capítulos para baixo
- **THEN** as ações principais da obra (voltar/favoritar/excluir/adicionar) SHALL continuar
  acessíveis de forma fixa

### Requirement: Separação estado e dado

O sistema SHALL manter o disco como fonte de verdade dos dados intrínsecos da obra (título,
`description`, `direction` detectada, ordem/título dos capítulos, página de capa,
`detectedLayout`) e SHALL manter no banco o estado pessoal (favorito, progresso, lido,
`directionOverride`, `layoutOverride`, `createdAt` de import).

#### Scenario: Override de direção é preferência
- **WHEN** o usuário sobrepõe a direção de leitura de uma obra
- **THEN** o `directionOverride` SHALL ser persistido no banco (estado), sem alterar a
  `direction` detectada no `work.json` (dado)

#### Scenario: Disco vence em divergência
- **WHEN** `work.json` e o índice do banco divergirem para um dado da obra
- **THEN** a reconstrução SHALL tratar o `work.json` como fonte de verdade

#### Scenario: Descrição propagada na reconstrução
- **WHEN** a biblioteca é reconstruída a partir de `obras/*/work.json`
- **THEN** o sistema SHALL propagar a `description` do `work.json` para o índice do banco
