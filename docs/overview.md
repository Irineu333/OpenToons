# OpenToons — Visão Geral

> Rede descentralizada e resistente à censura para compartilhamento de mangás.

Este documento apresenta a visão do projeto, o problema que ele resolve, o modelo
de ameaças, os princípios inegociáveis e os papéis dos participantes. Para o
detalhamento técnico, veja [`architecture.md`](./architecture.md); para as decisões
de projeto e alternativas descartadas, veja [`decisions/`](./decisions/README.md).

---

## 1. Problema

Scans (grupos que traduzem e publicam mangás) vivem sob pressão constante de
remoção: hospedagens caem, domínios são bloqueados, plataformas centralizadas
desligam contas. O conhecimento e o trabalho ficam reféns de pontos únicos de
falha, e não há mecanismo justo para que leitores retribuam a quem produz e a quem
mantém o conteúdo no ar.

O OpenToons propõe uma rede **sem servidor central**, onde:

- o conteúdo é **assinado** e, portanto, **impossível de falsificar**;
- a disponibilidade é **distribuída** entre os nós que sustentam a rede;
- remover conteúdo da rede inteira é **inviável** para um agente externo;
- leitores podem **doar diretamente** a quem sustenta o que consomem.

## 2. Princípios inegociáveis

Estas restrições moldam todas as decisões de arquitetura. Qualquer proposta que as
viole deve ser rejeitada.

| # | Princípio | Implicação |
|---|-----------|------------|
| P1 | **Sem servidor central** | Nenhuma peça pode ser ponto único de falha ou de censura. |
| P2 | **Sem dependência do cliente como nó pleno** | O leitor (mobile) consome sem precisar servir conteúdo. |
| P3 | **Todo conteúdo é assinado** | O cliente sempre verifica a assinatura antes de confiar. |
| P4 | **Sem centralização escondida** | Gateways, relays e bootstrap não podem virar gargalos disfarçados. |

## 3. Modelo de ameaças

O que a rede precisa resistir — e o que explicitamente **não** promete.

**Resiste a:**

- **Bloqueio de hospedagem/domínio** — não há domínio único; o conteúdo vive na malha.
- **Falsificação de conteúdo** — tudo é assinado; um impostor não forja a assinatura de uma scan.
- **Ataque de rollback** — servir uma versão antiga assinada como se fosse a atual é detectado (ver [ADR-0003](./decisions/0003-content-model.md)).
- **Remoção por terceiros** — nenhum agente externo consegue apagar o conteúdo de todos os nós.
- **Censura do catálogo por um nó** — o cliente consulta múltiplos nós e mescla resultados.
- **Bloqueio do bootstrap** — a lista de entrada é distribuída por múltiplos canais (ver [ADR-0007](./decisions/0007-resilient-bootstrap.md)).

**Não promete (limites honestos):**

- **Exclusão real e definitiva** — em rede replicada resistente à censura, "apagar" só
  pode significar *despublicar*; um nó que escolher preservar o conteúdo, preserva
  (ver [ADR-0004](./decisions/0004-deletion-semantics.md)). Isso é intrínseco, não é um defeito.
- **Anonimato forte de quem serve** — nós plenos têm endereço público e são, por
  natureza, observáveis.
- **Autenticidade de identidade sem ponto de partida de confiança** — vincular uma
  chave a uma scan "de verdade" ainda é um problema em aberto (ver [ADR-0008](./decisions/0008-identity-trust.md)).

## 4. Papéis

A rede é **P2P assimétrica**: não há autoridade, mas há diferenciação de papéis.
Quem sustenta a rede é distinto de quem apenas a consome (ver [ADR-0001](./decisions/0001-network-model.md)).

```
NÓS PLENOS (sustentam a malha)            NÓS LEVES (parasitam a malha)
┌───────────────────────────┐            ┌──────────────────────────┐
│ Scan (app desktop)         │            │ Leitor (app mobile)      │
│  · publica e assina obras  │            │  · apenas consome        │
│  · nó completo na rede     │            │  · não serve, não guarda │
│  · replica outras scans    │            │  · cliente da DHT        │
├───────────────────────────┤            │  · cache offline         │
│ Replicador (CLI / VPS)     │            └──────────────────────────┘
│  · nó completo na rede     │
│  · só replica (não publica)│
│  · pode receber doações    │
└───────────────────────────┘
```

- **Scan** — grupo produtor. Usa o app **desktop**, publica e assina o conteúdo,
  mantém um nó completo, replica automaticamente outras scans e configura seu
  teto de armazenamento e seu meio de doação.
- **Replicador** — voluntário que roda a **CLI** numa VPS. Nó completo que apenas
  replica conteúdo alheio, fortalecendo a disponibilidade da rede. Pode receber
  doações por sustentar a rede.
- **Leitor** — usa o app **mobile**. Um leitor de mangás completo com cache
  offline. Só consome; nunca é obrigado a servir.

## 5. Aplicações

| App | Público | Papel na rede | Função |
|-----|---------|---------------|--------|
| **Desktop** | Scans | Nó pleno | Publicar, gerenciar scan, replicar, servir |
| **Mobile** | Leitores | Nó leve (cliente DHT) | Ler mangás, cache offline, consumir a rede |
| **CLI** | Voluntários | Nó pleno | Replicar e servir; sustentar a rede |

## 6. Incentivos

O modelo de sustentação é **doação direta**, guiada por uma **pontuação calculada
localmente** com base no consumo real do usuário (ver [ADR-0009](./decisions/0009-scoring-and-donations.md)):

- quem **serviu** os blocos pontua a cada **download**; quem **publicou** pontua a
  cada capítulo **lido** (1× por mês); quem faz os dois soma;
- uma vez por mês o app destaca **um destinatário** — quem mais acumulou pontos
  desde a última doação — e a doação **reveza** entre eles ao longo do tempo;
- a doação vai **direto para a scan** (ou replicador), pelo meio que ela configurar;
- **não há pagamento automático** — o humano decide. Isso evita o problema
  (não resolvido de forma trustless) de *provar* que um nó realmente serviu bytes.

## 7. Tecnologia

O projeto é **Kotlin Multiplatform + Compose Multiplatform**, compartilhando lógica
e UI entre desktop (JVM) e mobile (Android). A camada de rede (DHT, troca de
blocos, endereçamento por conteúdo) baseia-se em conceitos de **libp2p/IPFS**; as
bibliotecas concretas serão validadas na **Prova de Conceito** (marco 0 do
[roadmap](./roadmap.md)).

## 8. Glossário

| Termo | Definição |
|-------|-----------|
| **Nó pleno** | Nó que guarda, serve e roteia conteúdo (scan desktop ou CLI). |
| **Nó leve** | Nó que apenas consulta e consome (mobile); cliente da DHT. |
| **DHT** | Tabela hash distribuída, usada para descoberta de nós e conteúdo. |
| **DHT client / DHT server** | Cliente faz consultas; servidor guarda rotas e roteia para outros. O mobile é *client*. |
| **CID** | *Content Identifier* — endereço imutável derivado do hash do conteúdo. |
| **Manifesto** | Documento assinado que descreve o estado atual de uma scan (obras, capítulos, CIDs). |
| **obra_id** | Identificador estável de uma obra = `(chave da scan, UUID)`. Sobrevive a novos capítulos. |
| **seq** | Número de sequência monotônico do manifesto; protege contra rollback. |
| **PEX** | *Peer Exchange* — nós trocam entre si listas de outros nós vivos. |
| **Bootstrap** | Nós/lista de entrada usados apenas para o primeiro contato com a rede. |
| **Hole punching** | Técnica para estabelecer conexão direta entre nós atrás de NAT. |
