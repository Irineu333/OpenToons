# content-import Specification

## Purpose
TBD - created by syncing change offline-reader. Update Purpose after archive.

## Requirements
### Requirement: Importar obra de arquivo local

O sistema SHALL permitir importar obras a partir de arquivos locais, via seletor de
arquivo nativo (Android, Desktop, iOS): `cbz` e `cbr` (**unidade** — imagens diretas) e
`zip` e `rar` (**pacote** — arquivos `.cbz`/`.cbr` internos, cada um um capítulo). O
seletor SHALL restringir a seleção às extensões **suportadas pela plataforma**: `cbz` e
`zip` sempre; `cbr` e `rar` apenas onde há descompactação RAR (Desktop e Android). No iOS,
RAR é **não-objetivo**, então o seletor oferece só `cbz` e `zip`.

#### Scenario: Selecionar e importar um CBZ ou CBR
- **WHEN** o usuário aciona a importação e escolhe um `.cbz` ou `.cbr` no seletor
- **THEN** o sistema cria uma obra com seus capítulos na biblioteca

#### Scenario: Filtrar por extensão suportada
- **WHEN** o seletor de arquivo é aberto no Desktop ou Android
- **THEN** ele SHALL restringir a seleção às extensões `cbz`, `cbr`, `zip` e `rar`

#### Scenario: Seletor no iOS (RAR não-objetivo)
- **WHEN** o seletor de arquivo é aberto no iOS
- **THEN** ele SHALL oferecer apenas `cbz` e `zip` (RAR não é suportado no iOS)

#### Scenario: Desambiguar unidade vs pacote
- **WHEN** um container é importado
- **THEN** se suas entradas de topo forem arquivos `.cbz`/`.cbr`, o sistema SHALL tratá-lo
  como **pacote** (cada arquivo interno = um capítulo); se forem imagens, como **unidade**

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

No import da obra, o sistema SHALL gerar **uma vez** uma thumbnail de capa
`obras/{obraId}/cover.webp` a partir da página de capa referenciada no `work.json`. A
`cover.webp` SHALL ser um **artefato derivado (cache)** e SHALL **não** alterar os bytes das
páginas dos capítulos (nenhuma página é transcodificada). Adicionar capítulos a uma obra
existente SHALL **não** regenerar a capa (capítulos adicionados são anexados e não trocam a
capa da obra).

#### Scenario: Capa gerada no import
- **WHEN** uma nova obra é importada
- **THEN** o sistema SHALL gerar `obras/{obraId}/cover.webp` a partir da página de capa

#### Scenario: Capa não é regenerada ao adicionar capítulo
- **WHEN** um novo capítulo é adicionado a uma obra existente
- **THEN** o sistema SHALL **não** regenerar `obras/{obraId}/cover.webp` (a capa da obra
  permanece a gerada no import)

#### Scenario: Páginas permanecem STORED e intactas
- **WHEN** a capa é gerada
- **THEN** as páginas dos `.opz` SHALL permanecer STORED com bytes crus (sem compressão nem
  transcode)

### Requirement: Descompactação sob demanda e ordenação de páginas

A leitura em regime SHALL ler as páginas descompactando o **OPZ** sob demanda, página a
página, sem carregar o capítulo inteiro em memória. No import, o conteúdo de origem
(ZIP/RAR) SHALL ser descompactado para materializar o OPZ. As páginas SHALL ser ordenadas
por ordenação natural dos nomes das entradas.

#### Scenario: Ordenação natural
- **WHEN** um capítulo contém entradas `pag2.jpg` e `pag10.jpg`
- **THEN** `pag2.jpg` SHALL ser apresentada antes de `pag10.jpg`

#### Scenario: Leitura sob demanda a partir do OPZ
- **WHEN** o leitor exibe uma página
- **THEN** o sistema SHALL ler do OPZ apenas os bytes daquela página

### Requirement: Capítulos derivados da estrutura de pastas

Ao importar uma **unidade** (CBZ/CBR), o sistema SHALL derivar os capítulos da estrutura
de diretórios: cada diretório com imagens vira um capítulo; imagens na raiz viram um único
capítulo. Ao importar um **pacote** (ZIP/RAR), cada arquivo interno (`.cbz`/`.cbr`) vira um
capítulo. A ordem dos capítulos SHALL seguir a ordenação natural dos nomes.

#### Scenario: Unidade com pastas de capítulos
- **WHEN** um CBZ/CBR contém pastas, cada uma com as imagens de um capítulo
- **THEN** o sistema SHALL criar um capítulo por pasta, na ordem natural dos nomes

#### Scenario: Unidade plana
- **WHEN** um CBZ/CBR tem as imagens na raiz, sem subpastas
- **THEN** o sistema SHALL criar um único capítulo com todas as páginas

#### Scenario: Pacote de arquivos
- **WHEN** um ZIP/RAR contém arquivos `.cbz`/`.cbr`
- **THEN** o sistema SHALL criar um capítulo por arquivo interno, na ordem natural dos nomes

### Requirement: Modelo de dados alinhado ao manifesto futuro

O sistema SHALL modelar obra, capítulo e página de forma alinhada ao `obra_id` e ao
manifesto do ADR-0003. Cada capítulo SHALL referenciar o **seu próprio arquivo OPZ**. O
identificador de obra SHALL prever o par `(chave_publicador, UUID)`, com `chave_publicador`
**não populado** neste marco.

#### Scenario: Obra importada sem publicador
- **WHEN** uma obra é importada localmente
- **THEN** ela recebe um `UUID` estável e o campo `chave_publicador` permanece vazio,
  sem emitir evento de leitura

#### Scenario: Capítulo aponta o próprio OPZ
- **WHEN** um capítulo é persistido
- **THEN** ele SHALL referenciar o caminho do seu `.opz` em `obras/{obra}/{capítulo}.opz`

### Requirement: Formato interno OPZ

O sistema SHALL usar OPZ como formato interno único de armazenamento de capítulos. Um OPZ
SHALL ser um contêiner ZIP com as páginas armazenadas **STORED** (sem compressão) e um
`manifest.json` com a ordem das páginas, layout detectado, direção e dimensões por página.
O escritor OPZ SHALL rodar em todas as plataformas sem dependência nativa de escrita.

#### Scenario: Materializar OPZ no import
- **WHEN** qualquer formato de origem é importado
- **THEN** o sistema SHALL gravar um `.opz` por capítulo, com páginas STORED e um manifesto

#### Scenario: Roundtrip de leitura
- **WHEN** um OPZ recém-escrito é aberto para leitura
- **THEN** o sistema SHALL listar e ler suas páginas via `openZip`, nas três plataformas

### Requirement: Armazenamento por capítulo

O sistema SHALL armazenar cada capítulo como um arquivo próprio no padrão
`obras/{obra}/{capítulo}.opz` dentro do storage do app.

#### Scenario: Layout de arquivos por obra
- **WHEN** uma obra com N capítulos é importada
- **THEN** o storage SHALL conter N arquivos `.opz` sob a pasta da obra

### Requirement: Adicionar capítulos a uma obra existente

O sistema SHALL permitir importar arquivos-**unidade** (`cbz`/`cbr`) para dentro de uma
obra já existente, acrescentando novos capítulos/volumes. Pacotes (ZIP/RAR) NÃO SHALL ser
aceitos nesse fluxo. Os novos capítulos SHALL ser normalizados para OPZ e anexados após os
existentes, sem reescrever os capítulos anteriores.

#### Scenario: Adicionar capítulo pelo detalhe da obra
- **WHEN** o usuário, na tela de detalhe, importa um `.cbz`/`.cbr`
- **THEN** o sistema SHALL criar novos `.opz` na pasta da obra e anexá-los à lista de capítulos

#### Scenario: Pacote recusado dentro da obra
- **WHEN** o usuário tenta importar um `.zip`/`.rar` dentro de uma obra
- **THEN** o sistema SHALL recusar a operação com mensagem clara

### Requirement: Descompactação RAR4 no Desktop/Android; RAR no iOS e RAR5 não-objetivos

O sistema SHALL descompactar arquivos RAR (CBR e pacotes RAR) no formato **RAR4** no
**Desktop e Android**, usando `junrar`. **RAR no iOS** é **não-objetivo**: o iOS SHALL
recusar RAR no import com mensagem clara (e o seletor não oferece RAR). Arquivos **RAR5**
são **não-objetivos** em todas as plataformas e SHALL ser recusados no import com mensagem
clara. A descompactação RAR SHALL ocorrer **apenas no caminho de import**; a leitura em
regime não depende de RAR.

#### Scenario: Importar CBR RAR4 (Desktop/Android)
- **WHEN** o usuário importa um `.cbr` em formato RAR4 no Desktop ou Android
- **THEN** o sistema SHALL extrair suas páginas e materializar OPZ

#### Scenario: Recusar RAR5
- **WHEN** o usuário importa um arquivo em formato RAR5
- **THEN** o sistema SHALL recusar o import com mensagem indicando que RAR5 não é suportado

#### Scenario: RAR no iOS recusado (não-objetivo)
- **WHEN** um arquivo RAR (CBR ou pacote) chega ao import no iOS
- **THEN** o sistema SHALL recusar com mensagem clara, sem afetar a leitura de OPZ nem o
  import de CBZ/ZIP

### Requirement: Seam de fonte extensível

O sistema SHALL isolar a origem do conteúdo atrás de uma abstração `Source`, de modo
que uma nova origem (ex.: rede, no Marco 2) possa ser adicionada como implementação
sem alterar as camadas de leitura ou biblioteca.

#### Scenario: Nova origem sem alterar o leitor
- **WHEN** uma nova implementação de `Source` é adicionada
- **THEN** as camadas de render e biblioteca SHALL funcionar sem modificação
