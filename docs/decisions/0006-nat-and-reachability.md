# ADR-0006 — NAT e alcançabilidade: scans públicas, relay só fallback

**Status:** Aceito

## Contexto

NAT foi um ponto de atenção desde o início. A intuição inicial era que o NAT do
**mobile** seria um problema. Na verdade, o NAT relevante é outro.

Fato de rede: um nó atrás de NAT fazendo conexão **de saída** não tem problema (é o
que o navegador faz o tempo todo). NAT só bloqueia conexões **de entrada** (alguém
querendo *discar* você).

```
mobile (atrás de NAT) ── disca ─▶ scan (pública)   ✅ saída, livre
mobile (atrás de NAT) ◀─ disca ── qualquer um        ❌ entrada bloqueada (e ok: mobile não serve)
```

## Decisão

- **Nós plenos (scans/CLIs) têm endereço público**, obtido via **hole punching**
  (AutoNAT para descobrir a situação de NAT + DCUtR para furar), com **circuit relay
  v2** como *fallback* apenas para nós que não conseguirem furar o NAT.
- **O mobile só faz conexões de saída** direto ao detentor do conteúdo. Seu NAT é
  irrelevante e ele **nunca precisa de relay para ler**.
- **O relay nunca está no caminho de leitura do mobile** — ele é, no máximo, um
  fallback de alcançabilidade entre nós plenos.

Roteamento + retrieval do mobile (padrão *delegated routing + direct fetch*):

```
1. mobile ─▶ DHT: "quem provê CID_cap42?"   (roteamento delegado, barato)
2. DHT     ─▶ "Scan B e CLI C têm"          (provider records)
3. mobile ─▶ disca B/C direto e baixa       (retrieval direto, saída)
```

## Alternativas consideradas

### Relay como proxy obrigatório de retrieval — descartada

O mobile buscaria conteúdo *através* de um relay que faz a busca e repassa os bytes.

- **Por que descartada:** delega o retrieval a um terceiro, criando gargalo e vetor
  de centralização (P4). Se poucos relays servissem todo mundo, a centralização
  voltaria disfarçada. O mobile deve baixar **direto do detentor**.

### Furar o NAT do mobile / mobile aceitar entrada — descartada

Tentar tornar o mobile discável.

- **Por que descartada:** desnecessário (o mobile só precisa de saída) e custoso
  (bateria, complexidade). O mobile não serve, então não há razão para aceitar
  entrada.

## Consequências

- Scans/CLIs precisam de suporte robusto a AutoNAT/DCUtR/relay-v2 (validar na PoC).
- A saúde da rede deve ser monitorada para evitar que **poucas scans virem os relays
  de todos** — centralização escondida (tema de observabilidade no marco 4).
- Como todos os detentores são nós plenos públicos, o mobile sempre consegue discá-
  los diretamente.
