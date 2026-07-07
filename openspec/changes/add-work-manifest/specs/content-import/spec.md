## ADDED Requirements

### Requirement: Manifesto de obra em disco

No import de uma **nova obra**, o sistema SHALL escrever um manifesto de obra
`obras/{obraId}/work.json` como **fonte de verdade dos dados intrínsecos da obra**,
contendo ao menos: `version`, `obraId`, `title`, `direction` (detectada) e `cover`
(referência `{chapterId, entryName}` à página de capa). O campo `chavePublicador` SHALL
ser previsto e **nulo** neste marco (reservado para o manifesto assinado do Marco 2).

#### Scenario: Import escreve o manifesto de obra
- **WHEN** uma nova obra é importada
- **THEN** o sistema SHALL criar `obras/{obraId}/work.json` com `title`, `direction`, `cover`
  e `obraId`, antes de indexar a obra no banco

#### Scenario: Manifesto é auto-descritivo
- **WHEN** apenas a pasta `obras/{obraId}/` existe (sem banco)
- **THEN** `work.json` SHALL conter o suficiente para identificar a obra (título, capa,
  direction) sem consultar o banco

### Requirement: chapterId estável no capítulo

O `manifest.json` de cada capítulo OPZ SHALL conter um `chapterId` (uuid) **estável**,
independente do nome do arquivo. O **nome do `.opz`** SHALL representar o **título/ordem** do
capítulo (usado para exibição e ordenação natural), enquanto o `chapterId` interno SHALL ser
a **chave estável de estado** (progresso, lido).

#### Scenario: Progresso sobrevive a rename
- **WHEN** o arquivo `.opz` de um capítulo é renomeado
- **THEN** o progresso e o estado do capítulo SHALL permanecer associados pelo `chapterId`
  interno, não pelo nome do arquivo

#### Scenario: direction ausente do manifesto do capítulo
- **WHEN** o `manifest.json` de um capítulo é escrito
- **THEN** ele SHALL conter `chapterId` e `detectedLayout`, e SHALL **não** conter `direction`
  (que passa a viver no `work.json` da obra)

### Requirement: Capa de obra derivada

No import da obra **e a cada capítulo adicionado**, o sistema SHALL (re)gerar uma thumbnail
de capa `obras/{obraId}/cover.webp` a partir da página de capa referenciada no `work.json`. A
`cover.webp` SHALL ser um **artefato derivado (cache) regenerável** e SHALL **não** alterar os
bytes das páginas dos capítulos (nenhuma página é transcodificada).

#### Scenario: Capa gerada no import
- **WHEN** uma nova obra é importada
- **THEN** o sistema SHALL gerar `obras/{obraId}/cover.webp` a partir da página de capa

#### Scenario: Capa regenerada ao adicionar capítulo
- **WHEN** um novo capítulo é adicionado a uma obra existente
- **THEN** o sistema SHALL regenerar `obras/{obraId}/cover.webp` e manter o `work.json`
  coerente

#### Scenario: Páginas permanecem STORED e intactas
- **WHEN** a capa é gerada
- **THEN** as páginas dos `.opz` SHALL permanecer STORED com bytes crus (sem compressão nem
  transcode)

## MODIFIED Requirements

### Requirement: Copy-in para storage próprio

No import, o sistema SHALL **materializar** o conteúdo no storage gerenciado do app em
formato OPZ por capítulo, mais o **manifesto de obra `work.json`** e a **capa `cover.webp`**
por obra, tornando-se dono do conteúdo. A leitura em regime SHALL usar exclusivamente o
storage próprio (OPZ), nunca a URI de origem do seletor nem o arquivo de origem.

#### Scenario: Origem removida após import
- **WHEN** o usuário apaga ou move o arquivo original após a importação
- **THEN** a obra continua legível a partir do storage próprio do app

#### Scenario: Cópia própria independente da origem
- **WHEN** um capítulo é importado
- **THEN** o sistema SHALL manter uma cópia própria (OPZ) que não depende de permissão
  contínua de acesso à origem

#### Scenario: Storage da obra é auto-descritivo
- **WHEN** uma obra é importada
- **THEN** `obras/{obraId}/` SHALL conter `work.json`, `cover.webp` e os `.opz` dos capítulos,
  suficientes para reconstruir a obra sem o banco
