## ADDED Requirements

### Requirement: Revisão de metadados antes de materializar

O import de uma **nova obra** SHALL apresentar uma etapa de **revisão** entre a seleção do
arquivo e a materialização, na qual o usuário SHALL poder editar **título**, **descrição** e
**capa** da obra. A materialização (gravar OPZ, `work.json`, `cover.webp` e indexar no banco)
SHALL ocorrer **somente após a confirmação** do usuário. Se o usuário cancelar a revisão, o
sistema SHALL **não gravar nada** — nenhum `.opz`, `work.json`, `cover.webp` ou registro no
banco — e a origem temporária SHALL ser descartada.

O sistema SHALL preparar a revisão **sem materializar**: abrir a origem, planejar os capítulos
em memória com um `chapterId` estável por capítulo, propor um **título default** (derivado do
nome do arquivo) e uma **capa default** (a 1ª página do 1º capítulo na ordem natural), e
oferecer as **páginas da própria obra** como candidatas a capa. Os `chapterId` gerados no
planejamento SHALL ser os mesmos usados na materialização, de modo que a capa escolhida
(referência `{chapterId, entryName}`) permaneça válida.

A escolha de capa SHALL ser restrita às **páginas da própria obra**: a capa resultante SHALL
sempre apontar uma página real via `{chapterId, entryName}` e a `cover.webp` SHALL seguir
sendo derivada dessa página (nenhuma imagem externa; nenhuma página transcodificada).

#### Scenario: Revisar antes de gravar
- **WHEN** o usuário seleciona um arquivo para importar uma nova obra
- **THEN** o sistema SHALL exibir uma etapa de revisão com título, descrição e capa editáveis,
  **antes** de gravar qualquer `.opz`, `work.json`, `cover.webp` ou registro no banco

#### Scenario: Cancelar não grava nada
- **WHEN** o usuário cancela a etapa de revisão
- **THEN** o sistema SHALL não deixar nenhum artefato em disco nem no banco para essa obra, e
  SHALL descartar a origem temporária

#### Scenario: Confirmar materializa com os valores editados
- **WHEN** o usuário confirma a revisão após editar título, descrição e/ou capa
- **THEN** o sistema SHALL materializar OPZ + `work.json` + `cover.webp` + banco usando os
  valores editados, com `work.json` como fonte de verdade

#### Scenario: Defaults quando nada é editado
- **WHEN** o usuário confirma a revisão sem alterar nada
- **THEN** o sistema SHALL usar o título derivado do nome do arquivo, a descrição vazia e a 1ª
  página do 1º capítulo como capa

#### Scenario: Capa escolhida entre as páginas da obra
- **WHEN** o usuário escolhe outra página como capa na revisão
- **THEN** a capa SHALL apontar essa página via `{chapterId, entryName}` e a `cover.webp` SHALL
  ser gerada a partir dela, sem transcodificar nenhuma página do conteúdo

## MODIFIED Requirements

### Requirement: Manifesto de obra em disco

No import de uma **nova obra**, o sistema SHALL escrever um manifesto de obra
`obras/{obraId}/work.json` como **fonte de verdade dos dados intrínsecos da obra**,
contendo ao menos: `version`, `obraId`, `title`, `description` (texto livre, opcional; vazio
por default), `direction` (detectada) e `cover` (referência `{chapterId, entryName}` à página
de capa). O campo `chavePublicador` SHALL ser previsto e **nulo** neste marco (reservado para
o manifesto assinado do Marco 2).

#### Scenario: Import escreve o manifesto de obra
- **WHEN** uma nova obra é importada
- **THEN** o sistema SHALL criar `obras/{obraId}/work.json` com `title`, `description`,
  `direction`, `cover` e `obraId`, antes de indexar a obra no banco

#### Scenario: Manifesto é auto-descritivo
- **WHEN** apenas a pasta `obras/{obraId}/` existe (sem banco)
- **THEN** `work.json` SHALL conter o suficiente para identificar a obra (título, descrição,
  capa, direction) sem consultar o banco

#### Scenario: Descrição opcional
- **WHEN** uma obra é importada sem descrição informada na revisão
- **THEN** `work.json` SHALL registrar `description` como texto vazio, e a obra SHALL
  permanecer válida
