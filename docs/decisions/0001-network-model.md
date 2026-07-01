# ADR-0001 — Modelo de rede: P2P assimétrico com papéis

**Status:** Aceito

## Contexto

Precisamos de uma rede sem servidor central (P1), resistente à censura, em que o
leitor consuma **sem** ser obrigado a servir conteúdo (P2). Ao mesmo tempo, alguém
precisa sustentar a disponibilidade dos arquivos. Há, portanto, uma assimetria
natural entre quem produz/sustenta (scans) e quem apenas consome (leitores).

## Decisão

Adotar uma rede **P2P assimétrica**: sem autoridade central, mas com **papéis
diferenciados**.

- **Nós plenos** — scans (desktop) e replicadores (CLI). Guardam, servem e roteiam.
  Sustentam a malha.
- **Nós leves** — leitores (mobile). Apenas consultam e consomem; nunca servem.

Chamamos o modelo informalmente de "federado" no sentido de que **apenas as scans
mantêm a rede** e o cliente a "parasita" — mas **sem** o conceito de instância-
autoridade típico de federações tradicionais.

## Alternativas consideradas

### Federação estilo Mastodon/ActivityPub (instâncias como autoridades) — descartada

Cada scan seria uma "instância" servidor, com contas de usuário e autoridade sobre
seus dados; instâncias federam entre si.

- **Por que descartada:** reintroduz **autoridade** por instância, o que conflita
  com a resistência à censura (uma instância vira ponto de controle/falha) e com o
  princípio de não haver centralização. O que queremos de "federação" é apenas a
  assimetria de papéis, não a autoridade.

### P2P simétrico (todo nó é pleno, inclusive o mobile) — descartada

Todos os participantes seriam nós plenos, servindo o que consomem (modelo
BitTorrent clássico).

- **Por que descartada:** viola P2 (o leitor não deve depender de participar como
  nó pleno). No mobile, servir implica custo de bateria, dados e lidar com NAT de
  entrada — inaceitável para um leitor. Ver [ADR-0005](./0005-mobile-client.md).

## Consequências

- A rede tem duas classes de nó, com implicações distintas de descoberta e NAT
  (ver [ADR-0005](./0005-mobile-client.md) e [ADR-0006](./0006-nat-and-reachability.md)).
- A saúde da rede depende de haver nós plenos suficientes — daí a importância dos
  incentivos ([ADR-0009](./0009-scoring-and-donations.md)) e da CLI de replicação.
- "Federação" no projeto significa **grafo de replicação opt-in entre scans**, não
  instâncias-autoridade (ver [ADR-0002](./0002-three-planes.md)).
