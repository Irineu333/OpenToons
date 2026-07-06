## ADDED Requirements

### Requirement: Importar obra de arquivo local

O sistema SHALL permitir importar obras a partir de arquivos locais no formato
CBZ, CBR ou ZIP, usando um seletor de arquivo nativo comum a todas as plataformas
(Android, Desktop, iOS).

#### Scenario: Selecionar e importar um CBZ
- **WHEN** o usuário aciona a importação e escolhe um arquivo `.cbz` no seletor
- **THEN** o sistema cria uma obra com seus capítulos na biblioteca

#### Scenario: Filtrar por extensão suportada
- **WHEN** o seletor de arquivo é aberto
- **THEN** ele SHALL restringir a seleção às extensões `cbz`, `cbr` e `zip`

### Requirement: Copy-in para storage próprio

No import, o sistema SHALL copiar os bytes do arquivo para o storage gerenciado do
app, tornando-se dono do conteúdo. A leitura em regime SHALL usar exclusivamente o
storage próprio, nunca a URI de origem do seletor.

#### Scenario: Origem removida após import
- **WHEN** o usuário apaga ou move o arquivo original após a importação
- **THEN** a obra continua legível a partir do storage próprio do app

#### Scenario: Cópia imutável
- **WHEN** um capítulo é importado
- **THEN** o sistema SHALL manter uma cópia própria que não depende de permissão
  contínua de acesso à origem

### Requirement: Descompactação sob demanda e ordenação de páginas

O sistema SHALL ler as páginas de um capítulo descompactando o arquivo sob demanda,
página a página, sem carregar o capítulo inteiro em memória. As páginas SHALL ser
ordenadas por ordenação natural dos nomes das entradas.

#### Scenario: Ordenação natural
- **WHEN** um capítulo contém entradas `pag2.jpg` e `pag10.jpg`
- **THEN** `pag2.jpg` SHALL ser apresentada antes de `pag10.jpg`

#### Scenario: Leitura sob demanda
- **WHEN** o leitor exibe uma página
- **THEN** o sistema SHALL ler da entrada apenas os bytes daquela página

### Requirement: Modelo de dados alinhado ao manifesto futuro

O sistema SHALL modelar obra, capítulo e página de forma alinhada ao `obra_id` e ao
manifesto do ADR-0003. O identificador de obra SHALL prever o par
`(chave_publicador, UUID)`, com `chave_publicador` **não populado** neste marco.

#### Scenario: Obra importada sem publicador
- **WHEN** uma obra é importada localmente
- **THEN** ela recebe um `UUID` estável e o campo `chave_publicador` permanece vazio,
  sem emitir evento de leitura

### Requirement: Seam de fonte extensível

O sistema SHALL isolar a origem do conteúdo atrás de uma abstração `Source`, de modo
que uma nova origem (ex.: rede, no Marco 2) possa ser adicionada como implementação
sem alterar as camadas de leitura ou biblioteca.

#### Scenario: Nova origem sem alterar o leitor
- **WHEN** uma nova implementação de `Source` é adicionada
- **THEN** as camadas de render e biblioteca SHALL funcionar sem modificação
