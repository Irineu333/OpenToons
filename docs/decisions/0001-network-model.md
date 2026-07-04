# ADR-0001 — Modelo de rede: P2P assimétrico com papéis

**Status:** Aceito

## Contexto

Precisamos de uma rede sem servidor central (P1), sem ponto único de falha, em que
o leitor consuma **sem** ser obrigado a servir conteúdo (P2). Ao mesmo tempo, alguém
precisa sustentar a disponibilidade dos arquivos. Há, portanto, uma assimetria
natural entre quem produz/sustenta (publicadores) e quem apenas consome (leitores).

## Decisão

Adotar uma rede **P2P assimétrica**: sem autoridade central, mas com **papéis
diferenciados**.

- **Nós plenos** — publicadores (desktop) e replicadores (CLI). Guardam, servem e
  roteiam. Sustentam a malha.
- **Nós leves** — leitores (mobile). Apenas consultam e consomem; nunca servem.

Chamamos o modelo informalmente de "federado" no sentido de que **apenas os
publicadores mantêm a rede** e o cliente a "parasita" — mas **sem** o conceito de
instância-autoridade típico de federações tradicionais.

### Autoria e serviço são separáveis (semeadura transitória)

Entre os nós plenos, **autorar/assinar** e **servir 24/7** não precisam morar na
mesma máquina. O padrão recomendado para um publicador é:

- o **desktop** autora, guarda a chave privada, assina e publica o manifesto —
  **e semeia**: sobe o conteúdo para os replicadores do próprio publicador;
- um ou mais **nós CLI (VPS)** replicam e servem de forma **persistente**.

A semeadura é **transitória**: o desktop só precisa ficar online o tempo suficiente
para seus replicadores puxarem os blocos novos (padrão *seeder* do BitTorrent — o
semeador inicial pode sair depois que outros têm o conteúdo). É a **mesma troca de
blocos** (Bitswap) de qualquer replicação, então **não exige mecanismo novo**.

Consequência importante: como o publicador **controla as duas pontas** (seu desktop
e sua VPS), a sincronização pode ser um **push de saída** do desktop para o endereço
público conhecido da VPS. Conexão de saída **não sofre NAT**, então o **desktop
nunca precisa de endereço público nem da DHT para semear** — mantém-se atrás do NAT,
só com saída (coerente com [ADR-0006](./0006-nat-and-reachability.md) e com o Tor
opcional previsto para o marco 4 — de-riscado pelo
[poc-05](../poc05-report.md): o publicador anônimo sobre Tor empurra por `push` à VPS,
E2E provado sobre Tor real). Servir diretamente a leitores durante a janela é
possível, mas é **bônus**, não requisito: o caminho recomendado é desktop → VPS →
rede.

"Publicador servindo o próprio conteúdo" e "replicador voluntário" são, portanto, o
**mesmo tipo de nó (CLI)** — muda apenas quem o opera.

## Alternativas consideradas

### Federação estilo Mastodon/ActivityPub (instâncias como autoridades) — descartada

Cada publicador seria uma "instância" servidor, com contas de usuário e autoridade
sobre seus dados; instâncias federam entre si.

- **Por que descartada:** reintroduz **autoridade** por instância, o que conflita
  com a ausência de ponto único de falha ou de controle (P1/P4 — uma instância
  vira ponto de controle/falha) e com o princípio de não haver centralização. O
  que queremos de "federação" é apenas a assimetria de papéis, não a autoridade.

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
- "Federação" no projeto significa **grafo de replicação opt-in entre
  publicadores**, não instâncias-autoridade (ver [ADR-0002](./0002-three-planes.md)).
- **O desktop não precisa ser um servidor sempre-online.** A disponibilidade durável
  vive nos nós CLI; o desktop é semeador transitório.
- O app desktop precisa de um fluxo para **registrar e sincronizar com os
  replicadores do próprio publicador** (push de saída dos blocos novos).
