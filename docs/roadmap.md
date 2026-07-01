# OpenToons — Roadmap

Este roadmap descreve **marcos** (milestones), não uma lista de changes. Cada marco
é um estágio de maturidade com um objetivo claro e critérios de conclusão. A
implementação de cada marco será quebrada em propostas de mudança (OpenSpec) no
momento apropriado.

A ordem é deliberada: começamos entregando **valor isolado** (um leitor de mangás
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

**Escopo:**

- validar bibliotecas de **libp2p/IPFS** viáveis em Kotlin Multiplatform (JVM e Android);
- provar **hole punching** (scan atrás de NAT doméstico fica discável);
- provar **DHT client** no Android (consultar sem servir, escapar do bootstrap);
- provar **manifesto assinado** (assinar, verificar, detectar rollback via `seq`);
- provar **troca de blocos** direta entre nós (baixar um capítulo ponta a ponta).

**Concluído quando:** um capítulo assinado por um "desktop" é descoberto e baixado
por um "mobile" via DHT, com verificação de assinatura, atravessando NAT.

**Riscos que este marco elimina:** viabilidade de libp2p em KMP/Android, NAT no
mundo real, custo de bateria/dados do DHT client.

## Marco 1 — Leitor mobile completo (offline)

**Objetivo:** um leitor de mangás mobile de primeira linha, **sem rede ainda**.
Entrega valor imediato e amadurece a UX antes de acoplar a complexidade P2P.

**Escopo:**

- app mobile (Android) com Compose Multiplatform;
- importação/leitura local de obras e capítulos;
- **cache/biblioteca offline** completo;
- experiência de leitura polida (paginação, modos de leitura, favoritos, progresso);
- modelo de dados de obra/capítulo alinhado ao futuro `obra_id` e manifesto.

**Concluído quando:** um usuário lê mangás confortavelmente offline, com biblioteca
e favoritos, sem qualquer dependência de rede.

## Marco 2 — App desktop, v1 da rede e módulo de rede no mobile

**Objetivo:** a rede nasce. As scans passam a publicar; o leitor passa a consumir
da malha.

**Escopo:**

- **app desktop** para scans: publicar e assinar obras, gerenciar a scan,
  configurar teto de armazenamento, atuar como **nó pleno** (DHT server, público);
- **rede v1:** planos de anúncio, catálogo (manifesto assinado + `seq`) e conteúdo
  (troca de blocos); descoberta de nós via bootstrap + DHT + PEX + cache;
- **replicação entre scans** (opt-in, com teto);
- **módulo de rede no mobile:** o leitor do marco 1 ganha o **DHT client**,
  descoberta de catálogo (consulta a múltiplos nós + merge), roteamento e
  download direto do detentor, com verificação de assinatura.

**Concluído quando:** uma scan publica pelo desktop e um leitor descobre, baixa,
verifica e lê pelo mobile — sem servidor central em lugar nenhum.

## Marco 3 — CLI para VPS

**Objetivo:** fortalecer a disponibilidade e a resiliência da rede com nós plenos
dedicados, sempre online.

**Escopo:**

- **CLI** headless para rodar em VPS como **nó pleno** que apenas replica e serve;
- configuração de teto de armazenamento e de política de replicação;
- operação estável de longa duração (cache quente, bom cidadão da DHT, relay
  opcional de fallback para nós que não furam o NAT).

**Concluído quando:** uma VPS sobe a CLI, entra na rede, replica conteúdo de scans
e passa a servir leitores de forma confiável.

## Marco 4 — Polimento e v2 da rede

**Objetivo:** robustez, desempenho e correção do que a operação real do marco 2–3
revelar.

**Escopo (indicativo):**

- **rede v2:** melhorias de descoberta, roteamento e replicação **balanceada
  automaticamente**; otimização de bateria/dados no mobile;
- resiliência de bootstrap multi-canal endurecida;
- endereçar a **autenticidade de identidade** (ver [ADR-0008](./decisions/0008-identity-trust.md), hoje em aberto);
- observabilidade da saúde da rede (ex.: detectar centralização escondida em
  relays ou poucos nós dominantes).

**Concluído quando:** a rede se mantém saudável e balanceada sob uso real, sem
gargalos disfarçados.

## Marco 5 — Sistema de doações

**Objetivo:** fechar o ciclo de incentivos, sustentando quem produz e quem replica.

**Escopo:**

- **pontuação local** baseada em consumo real (pesos para publicador × servidor);
- **aba de doação** com **ranking** local e **recomendação de doação mais justa**
  (considerando pontuação e doações anteriores);
- **doação direta** à scan/replicador pelo meio configurado por cada um;
- doações também para **replicadores (CLI)** por sustentarem a rede.

**Concluído quando:** o usuário vê o quanto consumiu de cada scan e consegue doar
diretamente, de forma justa e sem intermediário central.

---

## Notas

- Os marcos **0** e **1** são independentes da rede e podem amadurecer em paralelo
  ao design fino dos planos de rede.
- Detalhes de incentivo/doação e de identidade são propositalmente empurrados para
  o fim: são as áreas com **decisões ainda em aberto** e as que menos bloqueiam o
  valor central (ler e publicar de forma resistente à censura).
