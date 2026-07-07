## ADDED Requirements

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
`direction` detectada, ordem/título dos capítulos, página de capa, `detectedLayout`) e SHALL
manter no banco o estado pessoal (favorito, progresso, lido, `directionOverride`,
`layoutOverride`, `createdAt` de import).

#### Scenario: Override de direção é preferência
- **WHEN** o usuário sobrepõe a direção de leitura de uma obra
- **THEN** o `directionOverride` SHALL ser persistido no banco (estado), sem alterar a
  `direction` detectada no `work.json` (dado)

#### Scenario: Disco vence em divergência
- **WHEN** `work.json` e o índice do banco divergirem para um dado da obra
- **THEN** a reconstrução SHALL tratar o `work.json` como fonte de verdade

## MODIFIED Requirements

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
