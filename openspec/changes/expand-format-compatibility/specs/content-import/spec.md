## MODIFIED Requirements

### Requirement: Importar obra de arquivo local

O sistema SHALL permitir importar obras a partir de arquivos locais em quatro formatos,
via seletor de arquivo nativo comum a todas as plataformas (Android, Desktop, iOS):
`cbz` e `cbr` (**unidade** — imagens diretas) e `zip` e `rar` (**pacote** — arquivos
`.cbz`/`.cbr` internos, cada um um capítulo). O seletor SHALL restringir a seleção a
essas quatro extensões.

#### Scenario: Selecionar e importar um CBZ ou CBR
- **WHEN** o usuário aciona a importação e escolhe um `.cbz` ou `.cbr` no seletor
- **THEN** o sistema cria uma obra com seus capítulos na biblioteca

#### Scenario: Filtrar por extensão suportada
- **WHEN** o seletor de arquivo é aberto
- **THEN** ele SHALL restringir a seleção às extensões `cbz`, `cbr`, `zip` e `rar`

#### Scenario: Desambiguar unidade vs pacote
- **WHEN** um container é importado
- **THEN** se suas entradas de topo forem arquivos `.cbz`/`.cbr`, o sistema SHALL tratá-lo
  como **pacote** (cada arquivo interno = um capítulo); se forem imagens, como **unidade**

### Requirement: Copy-in para storage próprio

No import, o sistema SHALL **materializar** o conteúdo no storage gerenciado do app em
formato OPZ, tornando-se dono do conteúdo. A leitura em regime SHALL usar exclusivamente
o storage próprio (OPZ), nunca a URI de origem do seletor nem o arquivo de origem.

#### Scenario: Origem removida após import
- **WHEN** o usuário apaga ou move o arquivo original após a importação
- **THEN** a obra continua legível a partir do storage próprio do app

#### Scenario: Cópia própria independente da origem
- **WHEN** um capítulo é importado
- **THEN** o sistema SHALL manter uma cópia própria (OPZ) que não depende de permissão
  contínua de acesso à origem

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

## ADDED Requirements

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

### Requirement: Descompactação RAR com cobertura RAR4 e recusa de RAR5

O sistema SHALL descompactar arquivos RAR (CBR e pacotes RAR) no formato **RAR4** em todas
as plataformas, usando `junrar` em JVM/Android e cinterop `unarr` em iOS/Native. Arquivos
**RAR5** SHALL ser recusados no import com mensagem clara — não são suportados neste marco.
A descompactação RAR SHALL ocorrer **apenas no caminho de import**; a leitura em regime não
depende de RAR.

#### Scenario: Importar CBR RAR4
- **WHEN** o usuário importa um `.cbr` em formato RAR4
- **THEN** o sistema SHALL extrair suas páginas e materializar OPZ, em qualquer plataforma

#### Scenario: Recusar RAR5
- **WHEN** o usuário importa um arquivo em formato RAR5
- **THEN** o sistema SHALL recusar o import com mensagem indicando que RAR5 não é suportado

#### Scenario: Degradação isolada por plataforma
- **WHEN** o cinterop RAR não estiver disponível numa plataforma
- **THEN** o import de RAR SHALL falhar com mensagem clara naquela plataforma, sem afetar a
  leitura de OPZ nem o import de CBZ/ZIP
