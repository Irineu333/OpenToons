# offline-library Specification

## Purpose
TBD - created by syncing change offline-reader. Update Purpose after archive.
## Requirements
### Requirement: Biblioteca em grid

O sistema SHALL apresentar a biblioteca como um grid de capas das obras importadas, como
tela inicial do app. A capa exibida SHALL vir da **thumbnail de obra** (`cover.webp`), sem
destrinchar o `.opz` de um capítulo por célula.

#### Scenario: Exibir obras importadas
- **WHEN** o usuário abre o app com obras na biblioteca
- **THEN** o sistema SHALL exibir um grid com a `cover.webp` de cada obra

#### Scenario: Grid não abre o OPZ por célula
- **WHEN** o grid renderiza as capas
- **THEN** cada capa SHALL ser carregada da `cover.webp` da obra, não de uma página dentro de
  um `.opz` de capítulo

### Requirement: Reconstrução da biblioteca a partir do disco

O banco SHALL ser um **índice reconstruível** dos dados da obra, e não a fonte de verdade
deles. O sistema SHALL poder reconstruir a biblioteca varrendo `obras/*/work.json`,
recriando os registros de obra e capítulo a partir do disco.

#### Scenario: Reconstruir sem banco
- **WHEN** a biblioteca é reconstruída a partir do disco (banco ausente ou recriado)
- **THEN** o sistema SHALL recriar cada obra e seus capítulos a partir de `work.json` e dos
  `.opz`, sem exigir dados que só existiam no banco

#### Scenario: Estado pessoal preservado na reconstrução
- **WHEN** a biblioteca é reconstruída e o estado pessoal (favorito, progresso, lido) ainda
  existe no banco
- **THEN** o sistema SHALL preservar esse estado casando por `obraId` e `chapterId`

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

### Requirement: Favoritos

O sistema SHALL permitir favoritar e desfavoritar obras, e essa marcação SHALL
persistir entre sessões.

#### Scenario: Favoritar uma obra
- **WHEN** o usuário favorita uma obra
- **THEN** a obra SHALL aparecer marcada como favorita nas próximas aberturas do app

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

