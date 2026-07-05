# OpenToons — Visão Geral

> Rede descentralizada e resiliente para publicação e distribuição de obras.

Este documento apresenta a visão do projeto, o problema que ele resolve, o modelo
de ameaças, os princípios inegociáveis e os papéis dos participantes. Para o
detalhamento técnico, veja [`architecture.md`](./architecture.md); para as decisões
de projeto e alternativas descartadas, veja [`decisions/`](./decisions/README.md).

---

## 1. Problema

Publicadores independentes dependem de plataformas centralizadas que podem, a
qualquer momento, mudar regras, encerrar contas, sair do ar ou simplesmente deixar
de existir. O trabalho de quem publica fica refém de pontos únicos de falha, e não
há um mecanismo justo para que leitores retribuam a quem produz e a quem mantém o
conteúdo no ar.

O OpenToons propõe uma rede **sem servidor central**, onde:

- o conteúdo é **assinado** e, portanto, **impossível de falsificar**;
- a disponibilidade é **distribuída** entre os nós que sustentam a rede — nenhum
  nó individual é indispensável;
- leitores podem **doar diretamente** a quem sustenta o que consomem.

## 2. Princípios inegociáveis

Estas restrições moldam todas as decisões de arquitetura. Qualquer proposta que as
viole deve ser rejeitada.

| # | Princípio | Implicação |
|---|-----------|------------|
| P1 | **Sem servidor central** | Nenhuma peça pode ser ponto único de falha ou de controle. |
| P2 | **Sem dependência do cliente como nó pleno** | O leitor (mobile) consome sem precisar servir conteúdo. |
| P3 | **Todo conteúdo é assinado** | O cliente sempre verifica a assinatura antes de confiar. |
| P4 | **Sem centralização escondida** | Gateways, relays e bootstrap não podem virar gargalos disfarçados. |

## 3. Modelo de ameaças

O que a rede precisa resistir — e o que explicitamente **não** promete.

**Resiste a:**

- **Desligamento de hospedagem/domínio** — não há domínio único; o conteúdo vive na malha.
- **Falsificação de conteúdo** — tudo é assinado; um impostor não forja a assinatura de um publicador.
- **Ataque de rollback** — servir uma versão antiga assinada como se fosse a atual é detectado (ver [ADR-0003](./decisions/0003-content-model.md)).
- **Desaparecimento de nós individuais** — o conteúdo permanece disponível enquanto houver nós que escolham replicá-lo.
- **Omissão de catálogo por um nó** — o cliente consulta múltiplos nós e mescla resultados.
- **Bloqueio do bootstrap** — a lista de entrada é distribuída por múltiplos canais (ver [ADR-0007](./decisions/0007-resilient-bootstrap.md)).
- **Impostor de publicador já seguido ou verificável** — pinning da chave no
  "seguir", follow direto pela chave (URI/QR de canais próprios), endosso público
  assinado de 1 salto e verificação opcional por domínio
  (ver [ADR-0008](./decisions/0008-identity-trust.md)).

**Não promete (limites honestos):**

- **Exclusão real e definitiva** — em rede replicada, "apagar" só pode significar
  *despublicar*; um nó que escolher preservar o conteúdo, preserva
  (ver [ADR-0004](./decisions/0004-deletion-semantics.md)). Isso é um limite
  técnico intrínseco de redes replicadas — não uma garantia oferecida a ninguém.
- **Anonimato de quem serve** — nós plenos têm endereço público e são, por
  natureza, observáveis e identificáveis.
- **Proteção contra obrigações legais** — a rede não protege publicadores nem
  operadores de nós das consequências do que publicam ou replicam. Quem publica
  deve deter os direitos sobre o que distribui.
- **Autenticidade de identidade sem ponto de partida de confiança** — um usuário
  sem nenhum follow, diante de um publicador sem domínio verificado e sem
  endossos, não tem como distinguir o original do impostor; restam a UI honesta
  (nome autodeclarado é alegação, não fato) e a barreira extra no fluxo de doação.
  Limitação assumida (ver [ADR-0008](./decisions/0008-identity-trust.md)).

## 3.1. Responsabilidade pelo conteúdo

O OpenToons é **infraestrutura neutra de distribuição**: o projeto entrega o
software e não hospeda, cura nem controla o que trafega na rede. A responsabilidade
pelo que é publicado é **inteira e exclusiva do publicador**, que deve **deter os
direitos necessários** sobre o conteúdo que distribui. A rede **não se destina a
contornar obrigações legais** e não oferece proteção contra as consequências de
publicações ilícitas. Cada nó decide livremente o que replica e serve.

## 4. Papéis

A rede é **P2P assimétrica**: não há autoridade, mas há diferenciação de papéis.
Quem sustenta a rede é distinto de quem apenas a consome (ver [ADR-0001](./decisions/0001-network-model.md)).

```
NÓS PLENOS (sustentam a malha)              NÓS LEVES (parasitam a malha)
┌────────────────────────────────┐         ┌──────────────────────────┐
│ Publicador (app desktop)       │         │ Leitor (app mobile)      │
│  · publica e assina obras      │         │  · apenas consome        │
│  · semeia p/ seus repl.        │         │  · não serve, não guarda │
│  · pode ficar offline          │         │  · cliente da DHT        │
├────────────────────────────────┤         │  · cache offline         │
│ Replicador (CLI / VPS)         │         └──────────────────────────┘
│  · nó completo, 24/7           │
│  · replica e serve             │
│  · do publicador OU voluntário │
│  · pode receber doações        │
└────────────────────────────────┘
```

- **Publicador** — pessoa ou grupo produtor. Usa o app **desktop** para publicar e
  assinar o conteúdo, guardar a chave e **semear** para os próprios replicadores.
  Não precisa ficar sempre online: a disponibilidade durável vive nos nós CLI (ver
  [ADR-0001](./decisions/0001-network-model.md)).
- **Replicador** — nó **CLI** rodando 24/7 (tipicamente numa VPS) que replica e
  serve. Pode ser operado **pelo próprio publicador** (seus semeadores
  persistentes) ou por um **voluntário** que fortalece a rede. Pode receber
  doações. É o **único** papel que precisa de endereço público.
- **Leitor** — usa o app **mobile**. Um leitor de obras completo com cache
  offline. Só consome; nunca é obrigado a servir.

## 5. Aplicações

| App | Público | Papel na rede | Função |
|-----|---------|---------------|--------|
| **Desktop** | Publicadores | Nó pleno (semeador transitório) | Publicar, assinar, gerenciar o perfil, semear p/ seus replicadores |
| **Mobile** | Leitores | Nó leve (cliente DHT) | Ler obras, cache offline, consumir a rede |
| **CLI** | Publicadores e voluntários | Nó pleno (servidor 24/7) | Replicar e servir; sustentar a rede |

## 6. Incentivos

O modelo de sustentação é **doação direta**, guiada por uma **pontuação calculada
localmente** com base no consumo real do usuário (ver [ADR-0009](./decisions/0009-scoring-and-donations.md)):

- quem **serviu** os blocos pontua a cada **download**; quem **publicou** pontua a
  cada capítulo **lido** (1× por mês); quem faz os dois soma;
- uma vez por mês o app destaca **um destinatário** — quem mais acumulou pontos
  desde a última doação — e a doação **reveza** entre eles ao longo do tempo;
- a doação vai **direto para o publicador** (ou replicador), pelo meio que ele
  configurar;
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
| **Nó pleno** | Nó que guarda, serve e roteia conteúdo (publicador desktop ou CLI). |
| **Nó leve** | Nó que apenas consulta e consome (mobile); cliente da DHT. |
| **DHT** | Tabela hash distribuída, usada para descoberta de nós e conteúdo. |
| **DHT client / DHT server** | Cliente faz consultas; servidor guarda rotas e roteia para outros. O mobile é *client*. |
| **CID** | *Content Identifier* — endereço imutável derivado do hash do conteúdo. |
| **Manifesto** | Documento assinado que descreve o estado atual de um publicador (obras, capítulos, CIDs). |
| **obra_id** | Identificador estável de uma obra = `(chave do publicador, UUID)`. Sobrevive a novos capítulos. |
| **seq** | Número de sequência monotônico do manifesto; protege contra rollback. |
| **PEX** | *Peer Exchange* — nós trocam entre si listas de outros nós vivos. |
| **Bootstrap** | Nós/lista de entrada usados apenas para o primeiro contato com a rede. |
| **Hole punching** | Técnica para estabelecer conexão direta entre nós atrás de NAT. Não é requisito da v1; avaliação adiada para o marco 4 (ver [ADR-0006](./decisions/0006-nat-and-reachability.md)). |
