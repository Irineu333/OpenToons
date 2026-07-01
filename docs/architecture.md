# OpenToons — Arquitetura

Este documento detalha a arquitetura técnica da rede OpenToons. Ele assume a
leitura prévia da [Visão Geral](./overview.md) e complementa os
[Registros de Decisão (ADRs)](./decisions/README.md), que justificam cada escolha
e listam as alternativas descartadas.

---

## 1. Visão em camadas

A arquitetura separa **três planos** independentes. Essa separação é o que permite
que o catálogo seja barato de replicar enquanto o conteúdo (pesado) fica sujeito a
teto de armazenamento (ver [ADR-0002](./decisions/0002-three-planes.md)).

```
┌─ PLANO DE ANÚNCIO ─ aberto, todos ──────────────────────────────────┐
│  DHT / gossip: "existe a scan X; head do manifesto = seq 42"          │
│  Barato. Ninguém precisa de permissão para anunciar.                  │
├─ PLANO DE CATÁLOGO ─ leve, replicável por todos ────────────────────┤
│  Manifesto assinado: obras, capítulos → CID, metadados, seq.         │
│  Minúsculo → replicar o catálogo GLOBAL é barato.                    │
│  Assinado → impossível falsificar. seq → anti-rollback.             │
├─ PLANO DE CONTEÚDO ─ pesado, réplica é ESCOLHA, com teto ───────────┤
│  Bytes das páginas endereçados por CID (IPFS/Bitswap).               │
│  Sujeito ao teto de armazenamento configurado por cada nó.          │
└──────────────────────────────────────────────────────────────────────┘
```

Regra central: **anunciar/descobrir é aberto; espelhar/guardar é opt-in por nó.**
A "federação" emerge como um grafo de quem-espelha-quem, decidido localmente por
cada scan. É isso que permite que uma scan rejeitada por uns continue viva por
outros.

## 2. Topologia da rede

```
                    ┌──────────────────────────────────────────────┐
                    │        BOOTSTRAP (fora da rede)              │
                    │  lista assinada, distribuída por N canais    │
                    │  (git, IPNS, DNS seed, pastebin, telegram)   │
                    └────────────────────────┬─────────────────────┘
                                             │ entrada a frio
        ┌────────────────────────────────────┼────────────────────────────────────┐
        │                                    │                                    │
   ┌────▼────┐        DHT server        ┌────▼────┐        DHT server        ┌────▼────┐
   │ Scan A  │◀── replica (escolha) ───▶│ Scan B  │◀── replica (escolha) ───▶│  CLI C  │
   │ desktop │    IPFS/Bitswap blocos   │ desktop │    IPFS/Bitswap blocos   │  (VPS)  │
   │ pública │                          │ pública │                          │ pública │
   └────▲────┘                          └────▲────┘                          └────▲────┘
        │                                    │                                    │
        │   consulta (DHT client) + baixa direto do detentor (sem relay)        │
        └────────────────────────────────────┼────────────────────────────────────┘
                                             │
                                    ┌─────────▼─────────┐
                                    │   Mobile (leve)   │  DHT client
                                    │  consulta, baixa  │  não serve, não guarda
                                    │  cache offline    │  atrás de NAT (só saída)
                                    └───────────────────┘
```

- **Nós plenos** (scans e CLIs) são **DHT servers**, têm **endereço público** e
  guardam/servem/roteiam conteúdo.
- **Nós leves** (mobile) são **DHT clients**: fazem consultas e baixam direto dos
  detentores, mas não guardam nada para ninguém nem aceitam conexões de entrada.

## 3. Identidade e modelo de conteúdo

Ver [ADR-0003](./decisions/0003-content-model.md) para o racional completo.

### 3.1. Identidade

- Cada **scan** é um par de chaves (ex.: Ed25519). A chave pública **é** a
  identidade da scan na rede.
- Cada **obra** tem um identificador estável: `obra_id = (chave_da_scan, UUID)`.
  Favoritar e seguir apontam para o `obra_id`, que **sobrevive a novos capítulos**.
- O **conteúdo** (páginas) é endereçado por **CID** (imutável, muda a cada
  alteração de bytes). O `obra_id` é estável; o CID não. São coisas distintas.

> **Consequência de projeto:** "Berserk da Scan A" e "Berserk da Scan B" são
> entidades **diferentes** (identidade é por scan). Agrupar a "mesma obra" de scans
> distintas na UI é um problema de apresentação deixado para depois.

### 3.2. Manifesto assinado

O cliente **só precisa da última versão**, não do histórico. Portanto o estado de
uma scan é um **manifesto assinado do estado atual** (e não um log que o cliente
precise reproduzir):

```
MANIFESTO DA SCAN  (assinado com a chave da scan)
  seq: 42                              ← sequência monotônica (anti-rollback)
  obras:
    - id: uuid-berserk
      meta: { título, capa → CID, tags, ... }
      capítulos: [ { n: 1 → CID }, { n: 2 → CID }, ... ]   ← apenas os VIVOS
    - id: uuid-outra
      ...
  assinatura: sig(chave_da_scan)
```

Dois mecanismos de segurança embutidos:

- **Anti-falsificação:** o manifesto é assinado. Um impostor não consegue produzir
  um manifesto válido para a chave de outra scan.
- **Anti-rollback:** sem o `seq`, um censor poderia servir um manifesto *antigo*
  (legítimo e assinado!) escondendo capítulos novos. O cliente memoriza o maior
  `seq` já visto por scan e **rejeita versões menores**.

### 3.3. Alteração e exclusão

Scans modificam suas obras livremente publicando um novo manifesto assinado.
**Replicar e aceitar a modificação é uma escolha de cada nó** (ver
[ADR-0004](./decisions/0004-deletion-semantics.md)):

```
scan publica "remover cap.5" (novo manifesto, seq maior, sem o cap.5)
     │
     ├─ nós honestos: atualizam o catálogo, param de anunciar o CID   ✅
     └─ nó-arquivo:   escolhe preservar e continua servindo o CID     ✅ (é a resistência à censura)
```

Em rede replicada resistente à censura, **exclusão real é impossível** — e isso é
intencional. "Excluir" significa **despublicar**: sai do catálogo da scan, mas os
bytes podem sobreviver em quem escolher preservá-los.

## 4. Descoberta e roteamento

### 4.1. Descoberta de nós (como escapar do bootstrap)

O bootstrap serve **apenas para o primeiro contato**. A lista de nós é mantida
fresca pela composição de quatro mecanismos — nenhum deles central
(ver [ADR-0005](./decisions/0005-mobile-client.md)):

```
① BOOTSTRAP         semente de partida a frio (vários, multi-canal)   ← só cold start
② DHT client        lookups iterativos descobrem nós vivos espalhados ← auto-balanceia
③ PEX               todo nó que você fala te dá mais nós vivos
④ cache persistente lembra nós entre sessões; liveness descarta mortos
```

Fluxo de um **DHT client** (o mobile) escapando do bootstrap:

```
1º boot:   bootstrap ─▶ "peers perto da chave K?" ─▶ recebe nós plenos
2:         disca esses nós ─▶ "peers ainda mais perto?" ─▶ recebe outros
3:         lookup iterativo → em ~2–3 saltos conhece nós de toda a rede
4:         CACHEIA os nós no device
próximo:   tenta o cache primeiro; bootstrap só se o cache morreu
```

> **Custo honesto:** o mobile é intermitente, então seu cache "esfria" mais que o
> de um nó pleno (sempre online). Ele cai no bootstrap **com mais frequência** que
> um nó pleno — mas não "sempre". Mitiga-se com cache generoso e PEX agressivo no
> reconnect.

### 4.2. Descoberta de conteúdo (catálogo)

Como cada nó pleno replica os manifestos dos outros, **cada nó tem a união dos
catálogos**. Logo, *qualquer* nó pleno responde "o que existe na rede":

```
mobile ── "buscar 'Berserk'" ──▶ nó pleno (já tem o catálogo global)
                                   └─▶ resultados de todas as scans conhecidas
```

**Mitigação de censura de catálogo:** um nó malicioso pode *omitir* resultados
(não pode falsificar — assinatura). O cliente consulta **2–3 nós e mescla**.

### 4.3. Roteamento de conteúdo (quem tem o arquivo)

Como a réplica tem teto, **nem todo nó tem todo arquivo**. Para baixar um capítulo,
o mobile precisa achar *quem o guarda* e então baixar **direto do detentor**
(sem relay — ver [ADR-0006](./decisions/0006-nat-and-reachability.md)):

```
1. mobile ─▶ consulta a DHT: "quem provê CID_cap42?"   (roteamento delegado, barato)
2. DHT     ─▶ "Scan B e CLI C têm"                      (provider records)
3. mobile ─▶ disca B/C direto e baixa os blocos         (retrieval direto, saída)
```

> A disponibilidade ("quem tem o quê") é dado **volátil**; mantê-la como consulta
> ao vivo na DHT evita inchar o catálogo. Embutir dicas de disponibilidade no
> manifesto foi considerado e adiado por esse motivo.

## 5. NAT e alcançabilidade

Ver [ADR-0006](./decisions/0006-nat-and-reachability.md).

O NAT que importa é o **das scans** (precisam ser discáveis), não o do mobile:

```
mobile (atrás de NAT) ── disca ─▶ scan (pública)     ✅ conexão de saída, livre
mobile (atrás de NAT) ◀─ disca ── qualquer um         ❌ entrada bloqueada (e ok: mobile não serve)
```

- **Scans/CLIs** obtêm endereço público via **hole punching** (AutoNAT + DCUtR),
  com **circuit relay v2** como *fallback* apenas para nós que não conseguem furar
  o NAT.
- **O relay nunca está no caminho de leitura do mobile.** O mobile só faz conexões
  de saída direto ao detentor; não delega retrieval a ninguém.

## 6. Confiança e verificação

- **Integridade/autenticidade do conteúdo:** todo manifesto e todo conteúdo é
  assinado; o cliente **sempre verifica** antes de confiar. Um nó malicioso não
  forja — no máximo omite (mitigado por consulta múltipla).
- **Autenticidade de identidade (chave → scan):** vincular uma chave pública à
  scan "real" (evitar impostor que copia nome e capa) é um **problema em aberto**.
  Candidatos: TOFU, registro assinado no bootstrap, teia de confiança. Ver
  [ADR-0008](./decisions/0008-identity-trust.md).

## 7. Resiliência do bootstrap

Mesmo com tudo descentralizado, se *todos* os nós de bootstrap forem bloqueados o
cold start falha. A resiliência vem de **multiplicidade de canais** (ver
[ADR-0007](./decisions/0007-resilient-bootstrap.md)):

```
- lista assinada distribuída por N canais (git, IPNS, pastebin, telegram)
- DNS seed (domínio → IPs), fácil de rotacionar
- mDNS na LAN (descobre nós plenos na rede local, zero internet)
- peers "fallback" hardcoded no app, atualizados a cada release
- último cache de peers do próprio usuário
```

## 8. Incentivos (pontuação e doação)

Ver [ADR-0009](./decisions/0009-scoring-and-donations.md). Resumo do modelo:

- **pontuação 100% local**, baseada no consumo real; premia quem publicou e quem
  serviu (pesos a definir);
- **ranking** = as scans/CLIs que *este usuário* mais consumiu (não é ranking
  global — isso exigiria agregação central);
- **doação direta** à scan pelo meio que ela configurar; o app **recomenda** um
  valor mais justo com base na pontuação e nas doações anteriores;
- **sem pagamento automático** — evita a necessidade de *provar* serviço de bytes
  de forma trustless (problema tipo Filecoin, caro e burlável).

## 9. Questões em aberto

| Tema | Status | Onde |
|------|--------|------|
| Autenticidade de identidade (chave → scan) | Em aberto | [ADR-0008](./decisions/0008-identity-trust.md) |
| Pesos da pontuação e UX de doação | Proposto, a refinar | [ADR-0009](./decisions/0009-scoring-and-donations.md) |
| Agrupar "mesma obra" de scans diferentes na UI | Adiado | §3.1 |
| Dicas de disponibilidade no catálogo vs consulta ao vivo | Adiado (consulta ao vivo por ora) | §4.3 |
| Bibliotecas concretas de libp2p/IPFS para KMP | A validar na PoC | [roadmap](./roadmap.md) marco 0 |
