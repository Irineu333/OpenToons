# ADR-0004 — Semântica de exclusão: despublicação por escolha

**Status:** Aceito

## Contexto

A scan precisa poder criar, editar e **excluir** obras e capítulos. Ao mesmo tempo,
a rede é **replicada e resistente à censura**: uma vez que os bytes estão em N nós,
não há como forçar sua remoção de todos eles. Há uma tensão direta entre
"deletabilidade real" e "resistência à censura" — as duas são fisicamente opostas.

## Decisão

"Excluir" significa **despublicar**, e **replicar/aceitar a modificação é uma
escolha de cada nó**:

- a scan publica um novo manifesto assinado (com `seq` maior) sem o item removido;
- nós honestos atualizam o catálogo e param de anunciar o CID;
- um nó que **escolher preservar** o conteúdo pode continuar servindo o CID.

Aceitamos explicitamente que **exclusão definitiva é impossível** nessa rede — e que
isso é uma **propriedade**, não um defeito: é a mesma mecânica que resiste à censura
de terceiros.

## Alternativas consideradas

### Exclusão real e garantida — descartada (impossível)

Prometer que "excluir" remove o conteúdo de toda a rede.

- **Por que descartada:** tecnicamente impossível em rede P2P replicada. Qualquer
  nó pode ter copiado os bytes; não há autoridade para forçar remoção. Prometer
  isso seria mentir para o usuário.

### Ilusão de exclusão (esconder do próprio usuário que o conteúdo persiste) — descartada

Fingir, na UX, que o conteúdo sumiu definitivamente.

- **Por que descartada:** perigoso e desonesto. A mesma tecnologia que resiste à
  censura de terceiros resiste ao "delete" da própria scan; esconder isso cria falsa
  expectativa de privacidade/controle. Preferimos ser honestos sobre os limites (ver
  [overview §3](../overview.md)).

## Consequências

- A "federação" vira um **grafo de replicação opt-in**: uma scan rejeitada por uns
  pode continuar viva por outros — resistência à censura na prática.
- A preservação de conteúdo "despublicado" tende a acontecer via nós-arquivo que
  escolhem mantê-lo (e, no futuro, possivelmente re-hospedagem sob nova identidade
  assinada — fora de escopo por ora).
- A UX de exclusão deve comunicar honestamente que "remover" = "parar de publicar",
  não "apagar da existência".
