## ADDED Requirements

### Requirement: Usabilidade do modal de revisão (telas pequenas e teclado)

O modal de revisão de metadados SHALL manter suas **ações primárias (Cancelar/Importar) sempre
visíveis e acionáveis**, independentemente da altura da tela ou da presença do teclado virtual: o
conteúdo editável SHALL rolar dentro de uma área própria enquanto o rodapé de ações permanece fixo,
e o modal SHALL respeitar os insets do teclado (IME) de modo que o rodapé não seja coberto por ele.

O usuário SHALL poder **dispensar o teclado** sem depender de um botão de sistema: tocar em qualquer
área do modal que não seja um campo de texto SHALL baixar o teclado (limpar o foco) **sem fechar** o
modal. Os campos de texto SHALL expor ações de teclado coerentes (avançar entre campos / concluir).

#### Scenario: Ações alcançáveis em tela pequena
- **WHEN** a revisão é exibida numa viewport curta (ex.: telefone pequeno ou orientação paisagem)
- **THEN** os botões **Cancelar** e **Importar** SHALL permanecer visíveis e acionáveis, e o
  conteúdo editável SHALL rolar sem empurrar as ações para fora da tela

#### Scenario: Ações não cobertas pelo teclado
- **WHEN** o teclado virtual está aberto sobre um campo da revisão
- **THEN** o rodapé de ações SHALL permanecer visível acima do teclado (não coberto por ele)

#### Scenario: Tocar fora do campo baixa o teclado
- **WHEN** o teclado está aberto e o usuário toca numa área do modal fora de qualquer campo de texto
- **THEN** o sistema SHALL dispensar o teclado (limpar o foco) **sem fechar** o modal

## MODIFIED Requirements

### Requirement: Revisão de metadados antes de materializar

O import de uma **nova obra** SHALL apresentar uma etapa de **revisão** entre a seleção do
arquivo e a materialização, na qual o usuário SHALL poder editar **título**, **descrição** e
**capa** da obra. A materialização (gravar OPZ, `work.json`, `cover.webp` e indexar no banco)
SHALL ocorrer **somente após a confirmação** do usuário. Se o usuário cancelar a revisão, o
sistema SHALL **não gravar nada** — nenhum `.opz`, `work.json`, `cover.webp` ou registro no
banco — e as origens temporárias (arquivo e eventual imagem de capa externa) SHALL ser descartadas.

O sistema SHALL preparar a revisão **sem materializar**: abrir a origem, planejar os capítulos
em memória com um `chapterId` estável por capítulo, propor um **título default** (derivado do
nome do arquivo) e uma **capa default** (a 1ª página do 1º capítulo na ordem natural), e
oferecer as **páginas da própria obra** como candidatas a capa.

A escolha de capa SHALL aceitar **duas fontes**: uma **página da própria obra** (default) ou uma
**imagem externa** selecionada pelo usuário. A capa resultante é uma **imagem autônoma** da obra,
sem dependência viva com capítulos: a `cover.webp` SHALL ser gerada a partir dos bytes da fonte
escolhida (página **ou** imagem externa), nunca transcodificando páginas do conteúdo. Quando a
fonte for uma página, o `work.json` SHALL registrar sua proveniência `{chapterId, entryName}`;
quando for imagem externa, SHALL registrar a capa como externa (sem referência de página).

#### Scenario: Revisar antes de gravar
- **WHEN** o usuário seleciona um arquivo para importar uma nova obra
- **THEN** o sistema SHALL exibir uma etapa de revisão com título, descrição e capa editáveis,
  **antes** de gravar qualquer `.opz`, `work.json`, `cover.webp` ou registro no banco

#### Scenario: Cancelar não grava nada
- **WHEN** o usuário cancela a etapa de revisão
- **THEN** o sistema SHALL não deixar nenhum artefato em disco nem no banco para essa obra, e
  SHALL descartar as origens temporárias (arquivo e eventual imagem de capa externa)

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
- **THEN** a `cover.webp` SHALL ser gerada a partir dessa página, sem transcodificar nenhuma
  página do conteúdo, e o `work.json` SHALL registrar a proveniência `{chapterId, entryName}`

#### Scenario: Capa a partir de imagem externa
- **WHEN** o usuário escolhe uma imagem externa como capa (ex.: o arquivo não traz capa própria)
- **THEN** a `cover.webp` SHALL ser gerada a partir dessa imagem e o `work.json` SHALL registrar
  a capa como externa (sem referência de página), permanecendo a obra válida e a capa autônoma

### Requirement: Manifesto de obra em disco

No import de uma **nova obra**, o sistema SHALL escrever um manifesto de obra
`obras/{obraId}/work.json` como **fonte de verdade dos dados intrínsecos da obra**,
contendo ao menos: `version`, `obraId`, `title`, `description` (texto livre, opcional; vazio
por default), `direction` (detectada) e `cover`. O campo `cover` SHALL registrar a **proveniência**
da capa — a página de origem `{chapterId, entryName}` quando extraída de um capítulo, ou a marca de
**capa externa** quando importada de uma imagem — sem constituir dependência viva: a capa da obra é
a `cover.webp` autônoma, e apagar o capítulo de origem SHALL **não** invalidar a capa. O campo
`chavePublicador` SHALL ser previsto e **nulo** neste marco (reservado para o manifesto assinado do
Marco 2).

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

#### Scenario: Capa externa registrada como proveniência
- **WHEN** a capa escolhida na revisão é uma imagem externa
- **THEN** `work.json.cover` SHALL indicar capa **externa** (sem `{chapterId, entryName}`) e a
  obra SHALL permanecer válida e auto-descritiva

### Requirement: Capa de obra derivada

No import da obra, o sistema SHALL gerar **uma vez** a capa `obras/{obraId}/cover.webp` a partir
dos **bytes da fonte de capa escolhida** — uma página da obra (default) ou uma imagem externa. A
`cover.webp` SHALL ser uma **imagem de capa durável e autônoma** da obra (arquivo auto-descritivo),
e SHALL **não** alterar os bytes das páginas dos capítulos (nenhuma página é transcodificada).
Adicionar capítulos a uma obra existente SHALL **não** regenerar a capa (capítulos adicionados são
anexados e não trocam a capa da obra).

#### Scenario: Capa gerada no import
- **WHEN** uma nova obra é importada
- **THEN** o sistema SHALL gerar `obras/{obraId}/cover.webp` a partir da fonte de capa escolhida
  (página ou imagem externa)

#### Scenario: Capa sobrevive à remoção do capítulo de origem
- **WHEN** o capítulo do qual a capa foi extraída é removido
- **THEN** `obras/{obraId}/cover.webp` SHALL permanecer válida (a capa é autônoma, não uma
  referência viva à página)

#### Scenario: Capa não é regenerada ao adicionar capítulo
- **WHEN** um novo capítulo é adicionado a uma obra existente
- **THEN** o sistema SHALL **não** regenerar `obras/{obraId}/cover.webp` (a capa da obra
  permanece a gerada no import)

#### Scenario: Páginas permanecem STORED e intactas
- **WHEN** a capa é gerada
- **THEN** as páginas dos `.opz` SHALL permanecer STORED com bytes crus (sem compressão nem
  transcode)
