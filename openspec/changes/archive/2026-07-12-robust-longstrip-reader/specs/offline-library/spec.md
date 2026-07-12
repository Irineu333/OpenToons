## MODIFIED Requirements

### Requirement: Continuar leitura e progresso persistido

O sistema SHALL persistir o progresso de leitura por capítulo e oferecer retomar a leitura
de onde parou. O progresso persistido SHALL ser expresso num espaço independente de layout:
número de página no modo paginado; par `(índice da página, fração dentro da página)` no long
strip. A posição retomada SHALL ser a mesma independentemente da largura da janela, da
densidade da tela e da plataforma em que o capítulo é reaberto.

#### Scenario: Retomar de onde parou
- **WHEN** o usuário reabre um capítulo parcialmente lido
- **THEN** o leitor SHALL retomar na posição registrada, com precisão dentro da página e não
  apenas no seu topo

#### Scenario: Retomar em outra largura de tela
- **WHEN** o usuário lê um capítulo long strip num dispositivo e o reabre noutro, ou gira a
  tela entre as sessões
- **THEN** o leitor SHALL retomar exibindo o mesmo conteúdo, apesar da diferença de largura

#### Scenario: Marcar capítulo lido
- **WHEN** o usuário conclui um capítulo
- **THEN** o sistema SHALL registrar o capítulo como lido e refletir isso no detalhe da obra
