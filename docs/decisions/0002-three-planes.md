# ADR-0002 — Separação em três planos (anúncio / catálogo / conteúdo)

**Status:** Aceito

## Contexto

A ideia inicial listava três tecnologias — "federação", "uma DHT para descoberta" e
"uma rede IPFS de compartilhamento" — como pilares aparentemente concorrentes. Na
prática elas se sobrepõem (IPFS **já é** uma DHT Kademlia por baixo) e brigam
(federação-autoridade × P2P sem autoridade). Além disso, há dois requisitos em
tensão: o catálogo precisa ser **barato de replicar** e o conteúdo é **pesado** e
sujeito a teto de armazenamento.

## Decisão

Estratificar a arquitetura em **três planos independentes**, em vez de três redes
concorrentes:

1. **Plano de anúncio** (aberto, todos): DHT/gossip anuncia a existência de
   publicadores e o `head` de seus manifestos. Barato; ninguém precisa de
   permissão para anunciar.
2. **Plano de catálogo** (leve, replicável por todos): manifesto assinado com
   obras, capítulos → CID, metadados e `seq`. Minúsculo → replicar o catálogo
   global é barato. Assinado → impossível falsificar.
3. **Plano de conteúdo** (pesado, opt-in com teto): os bytes das páginas,
   endereçados por CID via IPFS/Bitswap.

Regra central: **anunciar/descobrir é aberto; espelhar/guardar é opt-in por nó.**
A "federação" é o grafo de quem-espelha-quem, decidido localmente.

## Alternativas consideradas

### IPFS como camada única, sem distinção de planos — descartada

Tratar tudo (descoberta e bytes) apenas como "IPFS".

- **Por que descartada:** confunde dois problemas diferentes — **descoberta de
  catálogo** ("que obras existem") e **roteamento de blocos** ("onde estão os
  bytes"). Sem separar, o catálogo (que queremos barato e 100% replicado) fica
  acoplado ao conteúdo (que é caro e parcial). A separação também deixa claro que
  IPNS/roteamento não precisa carregar metadados de catálogo.

### DHT dedicada de descoberta **separada** da DHT do IPFS — descartada

Rodar uma segunda DHT só para o catálogo.

- **Por que descartada:** custo operacional e de manutenção de duas redes DHT sem
  ganho claro. O plano de anúncio precisa apenas divulgar `head`s de manifesto —
  algo que a DHT/gossip existente do IPFS/libp2p já suporta. Mantemos **uma** malha,
  com planos lógicos por cima.

## Consequências

- Replicar o catálogo **global** é viável para qualquer nó pleno → qualquer nó
  responde "o que existe" (ver [ADR-0003](./0003-content-model.md) e arquitetura §4.2).
- O conteúdo pode ter réplica parcial (teto) sem afetar a completude do catálogo.
- A distinção anúncio × catálogo × conteúdo guia diretamente o design de
  descoberta e roteamento (arquitetura §4).
