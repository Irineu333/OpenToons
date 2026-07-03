# OpenToons — Roadmap

Este roadmap descreve **marcos** (milestones), não uma lista de changes. Cada marco
é um estágio de maturidade com um objetivo claro e critérios de conclusão. A
implementação de cada marco será quebrada em propostas de mudança (OpenSpec) no
momento apropriado.

A ordem é deliberada: começamos entregando **valor isolado** (um leitor de obras
que funciona sozinho) e só então introduzimos a complexidade da rede, reduzindo o
risco a cada passo.

```
 0 ──▶ 1 ──▶ 2 ──▶ 3 ──▶ 4 ──▶ 5
 PoC   Leitor Desktop CLI  Poli- Doações
       mobile +rede  VPS   mento
```

---

## Marco 0 — Prova de Conceito

**Objetivo:** validar as premissas técnicas mais arriscadas antes de investir no
produto. É código descartável cujo produto é *conhecimento*, não features.

> **Status: concluído** (jul/2026) — change [poc-01](../openspec/changes/archive/2026-07-03-poc-01/proposal.md);
> resultados, medições e recomendação de stack em [poc01-report.md](./poc01-report.md).
>
> **Extensão (poc-02, concluída — jul/2026):** a recomendação de *rede própria* do poc-01
> motivou a change [poc-02](../openspec/changes/archive/2026-07-03-poc-02/proposal.md) — implementação própria
> da camada de rede (**sem framework P2P**) comparada ao nabu, com o mesmo critério E2E
> fechado por outra rede. **Recomendação: stack própria (Noise XX + RPC de frames +
> membership/gossip) para o Marco 2** — medições e matrizes em
> [poc02-report.md](./poc02-report.md).
>
> **Extensão (poc-03, concluída — jul/2026):** para fechar o buraco honesto de que o
> poc-01 testou o libp2p só na encarnação JVM capenga (nabu), a change
> [poc-03](../openspec/changes/archive/2026-07-03-poc-03/proposal.md) fez um benchmark justo do libp2p **de
> referência** (go-libp2p e rust-libp2p) via **bindings nativos** (gomobile; UniFFI+cargo-ndk).
> As duas variantes rodaram do binding ao E2E do Marco 0 no Moto g(30); **peso é o desempate:
> rust 8–11 MB/ABI cabe no teto, go 33–35 MB/ABI estoura**. Produto é conhecimento comparável,
> **não** decisão de stack. Resultados e matrizes em [poc03-report.md](./poc03-report.md).

**Escopo:**

- validar bibliotecas de **libp2p/IPFS** viáveis em Kotlin Multiplatform (JVM e Android);
- provar **nó pleno discável** com endereço público **configurado manualmente**
  (port forwarding; furo automático de NAT não é requisito — ver
  [ADR-0006](./decisions/0006-nat-and-reachability.md));
- provar **DHT client** no Android (consultar sem servir, escapar do bootstrap);
- provar **manifesto assinado** (assinar, verificar, detectar rollback via `seq`);
- provar **troca de blocos** direta entre nós (baixar um capítulo ponta a ponta).

**Concluído quando:** um capítulo assinado por um "desktop" (discável via endereço
público configurado manualmente) é descoberto e baixado por um "mobile" atrás de
NAT, via DHT, com verificação de assinatura.

**Riscos que este marco elimina:** viabilidade de libp2p em KMP/Android e custo de
bateria/dados do DHT client.

## Marco 1 — Leitor mobile completo (offline)

**Objetivo:** um leitor de obras mobile de primeira linha, **sem rede ainda**.
Entrega valor imediato e amadurece a UX antes de acoplar a complexidade P2P.

**Escopo:**

- app mobile (Android) com Compose Multiplatform;
- importação/leitura local de obras e capítulos;
- **cache/biblioteca offline** completo;
- experiência de leitura polida (paginação, modos de leitura, favoritos, progresso);
- modelo de dados de obra/capítulo alinhado ao futuro `obra_id` e manifesto;
- **registro local de leitura**: evento `{obra_id, capítulo, chave_publicador,
  timestamp}` emitido a cada capítulo lido — insumo do sistema de doações do
  marco 5 (ver [ADR-0009](./decisions/0009-scoring-and-donations.md)); o evento de
  **download** (que pontua quem serviu) nasce no marco 2, junto com a rede.

**Concluído quando:** um usuário lê obras confortavelmente offline, com biblioteca
e favoritos, sem qualquer dependência de rede.

## Marco 2 — App desktop, v1 da rede e módulo de rede no mobile

**Objetivo:** a rede nasce. Os publicadores passam a publicar; o leitor passa a
consumir da malha.

**Escopo:**

- **app desktop** para publicadores: publicar e assinar obras, gerenciar o perfil,
  configurar teto de armazenamento, atuar como **nó pleno** (DHT server, público);
- **rede v1:** planos de anúncio, catálogo (manifesto assinado + `seq`) e conteúdo
  (troca de blocos); descoberta de nós via bootstrap + **membership/gossip** + PEX + cache —
  o E3 do [poc-02](./poc02-report.md) mediu gossip × Kademlia em simulação (10–10.000 nós)
  e recomendou gossip, com **gatilho objetivo de migração para DHT**: quando
  `obras × réplicas` passar de ≈ 5.000 registros ativos (tráfego de re-anúncio ∝ registros,
  não ∝ nós) ou a malha passar de ~10.000 nós plenos — reavaliar no marco 4;
- **replicação entre publicadores** (opt-in, com teto);
- **módulo de rede no mobile:** o leitor do marco 1 ganha o **DHT client**,
  descoberta de catálogo (consulta a múltiplos nós + merge), roteamento e
  download direto do detentor, com verificação de assinatura.

**Concluído quando:** um publicador publica pelo desktop e um leitor descobre,
baixa, verifica e lê pelo mobile — sem servidor central em lugar nenhum.

## Marco 3 — CLI para VPS

**Objetivo:** fortalecer a disponibilidade e a resiliência da rede com nós plenos
dedicados, sempre online.

**Escopo:**

- **CLI** headless para rodar em VPS como **nó pleno** que apenas replica e serve;
- configuração de teto de armazenamento e de política de replicação;
- operação estável de longa duração (cache quente, bom cidadão da DHT).

**Concluído quando:** uma VPS sobe a CLI, entra na rede, replica conteúdo de
publicadores e passa a servir leitores de forma confiável.

## Marco 4 — Polimento e v2 da rede

**Objetivo:** robustez, desempenho e correção do que a operação real do marco 2–3
revelar.

**Escopo (indicativo):**

- **rede v2:** melhorias de descoberta, roteamento e replicação **balanceada
  automaticamente**; otimização de bateria/dados no mobile;
- avaliar **furo automático de NAT** (AutoNAT/DCUtR/relay v2) para publicadores
  que não conseguem configurar endereço público manualmente (ver
  [ADR-0006](./decisions/0006-nat-and-reachability.md));
- avaliar **fallback HTTP de consumo** — Caminho B do
  [ADR-0005](./decisions/0005-mobile-client.md) — como complemento ao DHT client;
- avaliar **transporte Tor/I2P opcional** para nós plenos (privacidade de rede);
- **allowlist/blocklist por obra** no replicador — controle do operador sobre o que
  replica, apoiado no identificador único de obra;
- resiliência de bootstrap multi-canal endurecida;
- endereçar a **autenticidade de identidade** (ver [ADR-0008](./decisions/0008-identity-trust.md), hoje em aberto);
- observabilidade da saúde da rede (ex.: detectar centralização escondida em
  relays ou poucos nós dominantes).

**Concluído quando:** a rede se mantém saudável e balanceada sob uso real, sem
gargalos disfarçados.

## Marco 5 — Sistema de doações

**Objetivo:** fechar o ciclo de incentivos, sustentando quem produz e quem replica.

**Escopo:**

- **pontuação local** lastreada no capítulo: quem serviu pontua no download (sem
  dedup), quem publicou pontua na leitura (1× por mês) — ver
  [ADR-0009](./decisions/0009-scoring-and-donations.md);
- **card mensal de doação**: um destinatário por vez — o topo do acumulador de
  pontos desde a última doação; "doei" zera, "pular" segue acumulando;
- **doação direta** pelo meio configurado por cada um, com metadados de pagamento
  **assinados** pela chave do destinatário;
- **replicadores (CLI)** participam da **mesma fila**: servir capítulos que foram
  lidos pontua.

**Concluído quando:** o usuário recebe o card mensal, entende a recomendação em uma
frase ("X capítulos = Y pontos") e consegue doar diretamente, sem intermediário
central.

---

## Notas

- Os marcos **0** e **1** são independentes da rede e podem amadurecer em paralelo
  ao design fino dos planos de rede.
- A mecânica de incentivo/doação já está decidida
  ([ADR-0009](./decisions/0009-scoring-and-donations.md)), mas sua implementação
  fica para o fim por bloquear pouco o valor central (ler e publicar sem depender
  de ponto único de falha) — com uma exceção: o **evento de leitura nasce no
  marco 1**. Identidade ([ADR-0008](./decisions/0008-identity-trust.md)) segue
  **em aberto** e é endereçada no marco 4.
