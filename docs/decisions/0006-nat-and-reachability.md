# ADR-0006 — NAT e alcançabilidade: endereço público configurado manualmente

**Status:** Aceito

## Contexto

NAT foi um ponto de atenção desde o início. A intuição inicial era que o NAT do
**mobile** seria um problema. Na verdade, o NAT relevante é outro.

Fato de rede: um nó atrás de NAT fazendo conexão **de saída** não tem problema (é o
que o navegador faz o tempo todo). NAT só bloqueia conexões **de entrada** (alguém
querendo *discar* você).

```
mobile (atrás de NAT) ── disca ─▶ publicador (público)  ✅ saída, livre
mobile (atrás de NAT) ◀─ disca ── qualquer um            ❌ entrada bloqueada (e ok: mobile não serve)
```

## Decisão

- **Nós plenos (publicadores/CLIs) têm endereço público, configurado manualmente**
  pelo operador: port forwarding no roteador, IP público ou VPS. A alcançabilidade
  é responsabilidade de quem opera o nó pleno; **nenhum mecanismo de furo
  automático de NAT é requisito**.
- **Furo automático de NAT** (AutoNAT + DCUtR, com circuit relay v2 como fallback)
  fica **adiado para avaliação no marco 4**, quando a operação real mostrar quantos
  publicadores de fato ficam presos atrás de NAT sem conseguir configurar o
  roteador. Nota: o [poc-05](../pocs/poc05-report.md) provou uma via alternativa para o
  publicador não-discável — **Tor + `push`** (o publicador anônimo, só saída, empurra
  blocos à VPS por dentro do circuito), que também resolve o NAT sem furo automático.
- **O mobile só faz conexões de saída** direto ao detentor do conteúdo. Seu NAT é
  irrelevante e ele **nunca precisa de relay para ler**.
- **Nada fica no caminho de leitura do mobile** — ele disca direto o endereço
  público do detentor; não delega retrieval a ninguém.

Roteamento + retrieval do mobile (padrão *delegated routing + direct fetch*):

```
1. mobile ─▶ DHT: "quem provê CID_cap42?"   (roteamento delegado, barato)
2. DHT     ─▶ "Pub. B e CLI C têm"          (provider records)
3. mobile ─▶ disca B/C direto e baixa       (retrieval direto, saída)
```

## Alternativas consideradas

### Furo automático de NAT como requisito da v1 (AutoNAT + DCUtR + relay v2) — adiada

Nós plenos obteriam endereço público automaticamente via hole punching, com relay
como fallback, sem exigir configuração do operador.

- **Por que adiada:** as stacks JVM disponíveis não oferecem DCUtR (e relay v2 é
  imaturo), então exigir furo automático travaria a PoC e a v1 da rede em
  engenharia de infraestrutura. Operar um nó pleno já é uma tarefa técnica
  (publicar, assinar, manter online); configurar port forwarding é um custo
  aceitável para esse perfil. A avaliação volta no **marco 4**, com dados reais de
  quantos publicadores ficam de fora por não conseguirem configurar.

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

- O app desktop e a CLI devem **orientar o operador** na configuração: detectar
  porta inacessível, instruir o port forwarding e oferecer um teste de
  alcançabilidade ("seu nó está discável?").
- A barreira de entrada para publicadores domésticos **aumenta** — é o custo
  aceito. Se a operação real mostrar que isso limita a adoção, o **marco 4**
  reavalia o furo automático (AutoNAT/DCUtR/relay v2).
- Sem relay na v1, não existe por ora o risco de centralização escondida em relays;
  o tema retorna junto com a avaliação do marco 4.
- Como todos os detentores são nós plenos públicos, o mobile sempre consegue discá-
  los diretamente.
