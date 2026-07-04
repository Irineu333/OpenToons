# Design: poc-05 — modo anônimo (publicador sobre Tor), backend trocável

## Context

O [ADR-0001](../../../docs/decisions/0001-network-model.md) já desenhou o modo anônimo sem nomeá-lo como POC: o publicador (desktop) fica atrás do NAT, **só com saída**, fazendo push de blocos para o endereço público conhecido da própria VPS — "coerente com o Tor opcional previsto para o marco 4". O [ADR-0006](../../../docs/decisions/0006-nat-and-reachability.md) reforça: o mobile só faz conexões de saída e nunca precisa de relay; o publicador atrás de NAT é o caso a "avaliar no Marco 4". O [poc-04](../../../docs/poc04-report.md) entregou o instrumento que torna esta POC barata: um **seam neutro com backend trocável** (`P2pBackend`/`FullNode`/`Blockstore` + TCK + matriz E2E), veredito `própria → rust-libp2p condicional a gatilho`. A poc-05 pega esse seam e o estende com o eixo que mais separa os dois backends: **transporte anônimo**.

Fatos das POCs anteriores que moldam o design:

- **O seam segura client E full node** (poc-04, Q1 "sim"): 3 pontos de vazamento documentados, zero branch, ~150 LoC de cola por adapter. A poc-05 **reusa** esse seam; não o re-prova.
- **A Trama é TCP+Noise+RPC de frames** (poc-02): túnel SOCKS5 é `java.net.Proxy` no dial, o resto do protocolo não fica sabendo. O handshake Noise XX **autentica a identidade Ed25519 do par** (impostor rejeitado antes de dados) — a defesa contra exit malicioso já existe e foi validada em clearnet.
- **O rust-libp2p é swarm QUIC-cêntrico** (poc-03/04): QUIC é UDP (não passa no Tor → desligar), o dialer SOCKS não vem pronto (Transport customizado no facade), e o swarm disca sozinho (identify, mDNS, multiaddrs `/dns/`) — cada um é superfície de vazamento a conter. O **pcap é o juiz**, não a intenção.
- **A replicação hoje é PULL** (poc-02/04: o replicador puxa e anuncia). Um publicador não-discável **não pode ser puxado** → o modo anônimo força um RPC de **push** que nenhuma POC tem. É superfície nova no seam.

```
              TOR                           CLEARNET                     NAT
  ┌────────────────────────┐      ┌───────────────────────────┐   ┌────────────┐
  │ P — publicador         │      │ B — bootstrap (IP público)│   │ M — Moto   │
  │  daemon Tor local      │ ───▶ │ R — replicador(IP público)│◀──│   g(30)    │
  │  0 listen, só saída     │ exit │  full nodes (:api)        │   │  dados     │
  │  pcap na interface      │  ou  │  logam IP de toda entrada │   │  móveis    │
  │  push via :api          │onion │  R aceita push autenticado│   │  client    │
  └────────────────────────┘      └───────────────────────────┘   └────────────┘
        :api (poc-04 + push)            EXATAMENTE UM backend por build
             ▲                                    ▲
     :trama (SOCKS5 + Noise+push)      :libp2p (Transport Tor + req-resp push)
```

## Goals / Non-Goals

**Goals:**

- Provar (ou refutar) que o publicador em **modo anônimo** fecha o E2E do produto (push → replicador clearnet → fetch no mobile) **sem vazar o IP do desktop**, com não-vazamento **auditado** (pcap + exits do consenso), não declarado.
- Provar os **dois cenários**: C1 (IP do replicador conhecido) e C2 (replicador **descoberto via Tor**, publicador conhece só o bootstrap) — este último provando que a *descoberta* também cabe no túnel.
- Fazer isso sobre os **dois backends reais** (Trama e rust-libp2p) atrás do mesmo seam, medindo a divergência em vez de assumi-la; responder as questões Q1–Q10 a priori com um **veredito**.
- Medir o **custo da abstração no eixo anônimo**: pontos de vazamento novos no seam (≤ 1), regressão de peso, LoC de cola, esforço por adapter (veto), robustez de circuito.
- Definir "anônimo" a priori e registrar seus **limites honestos** no estilo do `overview.md`.

**Non-Goals:**

- **Leitor mobile via Tor** — o roadmap escopa Tor a nós plenos; embarcar Tor no leitor (AAR de MBs + custo de bateria de circuito) destruiria as vitórias medidas (APK 0,92 MB, bateria desprezível). O mobile lê da VPS pela clearnet, como sempre.
- **I2P** — só-design registrado (mesmo padrão do WebRTC no poc-04): a Trama sobre I2P seria o mesmo desenho (SAM em vez de SOCKS); validar dois anonimatos dobra o custo sem dobrar o conhecimento. Se o Tor passar, I2P é variante; se falhar, herda as lições.
- **Anonimato absoluto / resistência a análise de tráfego e timing** — limite do próprio Tor, não validável numa POC; registrado no modelo de ameaças, jamais prometido.
- **Re-provar os motores** (baselines poc-02/04) ou re-medir bateria/dados de sessão longa (o wire é o mesmo; referenciar).
- **rust-libp2p sobre Tor como recomendação** — se couber, é dado; a decisão de stack continua a do poc-04. A poc-05 mede, não re-decide a stack base.
- Política fina de aceitação de push (allowlist por obra, Marco 4) — a POC prova só que a autenticação do canal Noise sustenta a decisão (aceita conhecido, rejeita chave errada).

## Questões a responder (Q1–Q10, fixadas a priori)

O relatório final responde uma a uma; são o contrato de conclusão da POC.

| # | Questão | Respondida por |
|---|---|---|
| **Q1** ⚑ | O publicador anônimo fecha o E2E (push → R clearnet → fetch no mobile) com **zero vazamento auditado** (pcap 0 pacotes fora do Tor; IPs vistos ⊆ exits)? | E4 (C1) |
| **Q2** ⚑ | A **descoberta através do Tor** funciona (C2: P conhece só B, descobre R nunca informado, por dentro do túnel)? | E3 + E4 (C2) |
| Q3 | O RPC de **push** cabe no seam do poc-04 **sem branch de app** e sem carregar endereço de origem no wire? | E0/E2 |
| Q4 | Quantos **pontos de vazamento novos** o modo anônimo adiciona ao `:api`? (proxy/onion como config de fábrica é ≤ 1 capability-flag?) | E5 |
| Q5 | O **não-vazamento** passa nos **dois** backends, ou algum swarm/DNS/mDNS vaza no pcap? | E1/E2 + auditoria |
| Q6 | Qual o **custo de latência** honesto do modo? (handshake, push de 768 KiB, lookup frio — todos degradados pelo circuito) | E4/E5 |
| Q7 | O modo anônimo **favorece a Trama**? (1 circuito/lookup × walk multi-dial; superfície auditável × swarm a conter; SOCKS trivial × Transport custoso) | síntese |
| Q8 | O **custo de esforço** por adapter cabe no veto (5 dias)? O transporte SOCKS/onion no rust estoura? | E2 + esforço |
| Q9 | A **robustez de circuito** segura? (morte de circuito no meio do push retoma sem intervenção) | E5 |
| **Q10** ★ | Veredito: modo anônimo **viável e abaixo do seam** / **viável mas vaza** / **inviável num backend** — e é **gatilho invertido** contra migrar ao libp2p? | síntese |

Encadeamento: Q1 "não" (vaza) → fim da tese para aquele backend. Q1/Q2 "sim" → Q3–Q9 dão o custo e a divergência → Q7/Q10 fecham o veredito.

## Decisions

### D0 — Definição de "anônimo" (fixada ANTES de qualquer experimento)

- **Protege:** o vínculo entre a *identidade Ed25519 do publicador* (pública por design — assina tudo) e o *IP/localização do desktop*. É **pseudonimato com privacidade de rede**, não anonimato absoluto.
- **Não protege (limite honesto, no estilo do `overview.md`):** a VPS continua pública e observável (P4); leitores continuam em clearnet; correlação por adversário global passivo / timing é limite do Tor; padrões temporais de publicação (timestamps em manifestos, horários de `seq`) podem revelar fuso/rotina. Registrar, jamais prometer.
- **Critério de sucesso do modo** = as quatro asserções de não-vazamento (D5), não "conectou via Tor".

### D1 — Reusar o seam do poc-04; estender com `push`, não redesenhar

O `:api` da poc-05 é o do poc-04 (`P2pBackend`/`FullNode`/`Blockstore` + tipos neutros + verify fora do seam + TCK) **mais** um método de replicação por empurrão. Alternativa considerada (redesenhar o seam para o modo anônimo) → descartada: mataria a continuidade com o poc-04 e o custo que se quer medir é *incremental* (o que o anônimo adiciona), não um seam novo.

- **`push(provider, manifest, blocks)`** no `FullNode` (ou num papel `Replicator` do seam): o publicador entrega conteúdo a um nó que **grava** no `Blockstore`. Espelho do `getBlocks` (que lê): o poc-04 já provou o callback rust→Kotlin de leitura (`BlockstoreCallback`); aqui o callback **escreve**. Requisito estrutural: o frame de push **não carrega endereço de origem** — manifesto tem obra/`seq`/CIDs/chave, nunca IP; o cliente Trama já não manda HELLO (poc-02).
- **Política de aceitação (mínima):** R aceita push só de conexão **autenticada pelo Noise/identidade Ed25519** e valida a assinatura do manifesto **antes** de gravar; push de chave errada é rejeitado. Fina (allowlist por obra) é non-goal.

### D2 — Config de anonimato é config de FÁBRICA de backend, nunca código de app

Proxy SOCKS/onion entra como parâmetro da criação do backend (como o `AnnounceTuning` do poc-04), consumido dentro do adapter. O app/nó declara "modo anônimo on/off + endpoint do daemon"; **como** isso vira circuito é do backend. Divergências de capacidade viram `Capability` consultável (candidato: `ANONYMOUS_DIAL`) — o padrão de vazamento nº 1 já documentado no poc-04. Meta: **≤ 1 ponto de vazamento novo** no inventário.

### D3 — Dois caminhos Tor: exit (P→clearnet) e onion (P→onion da VPS); medir os dois

```
exit:   P ─SOCKS5─▶ [guard·middle·exit] ─TCP─▶ IP_R:porta        (R vê IP de exit)
onion:  P ─SOCKS5─▶ [circuito 100% Tor] ─────▶ R.onion            (sem exit; R dual-homed)
```

- **exit** é o caminho nativo do Tor (dial de qualquer IP público). Risco: exit policies bloqueiam portas não-padrão (a faixa 4000–4999 das POCs pode atritar) → mitigável com porta 443 ou o caminho onion.
- **onion** (recomendado): R é **dual-homed** (IP público para leitores clearnet + onion service para P) — circuito 100% dentro do Tor, sem exit no caminho (elimina bloqueio de porta e a superfície do exit). Um listener a mais + `torrc` na VPS. Onion v3 usa **Ed25519** — a mesma curva da identidade do nó; registrar a possibilidade de derivar o endereço onion da identidade (unificação como o PeerId no poc-04).
- Se o exit atritar, rodar só onion e registrar como dado a favor (não bloqueio). Alternativa "só exit" descartada como padrão pela superfície do exit.

### D4 — Backend Trama: SOCKS5h (resolução remota) — a armadilha de DNS

O dial da Trama passa a aceitar um `Proxy(SOCKS, host:9050)`. **Armadilha a provar explicitamente:** `java.net.Socket` com proxy SOCKS pode resolver o hostname **localmente** antes de conectar (vazamento de DNS clássico). O adapter deve discar por **IP/onion com resolução remota (SOCKS5h)** ou nenhuma resolução — e o pcap prova ausência de qualquer pacote DNS. Alternativa (resolver no app e passar IP) → aceitável só se o IP for de bootstrap conhecido, mas onion não resolve localmente por construção (melhor). O handshake Noise segue idêntico — a cifra é fim-a-fim, indiferente ao proxy.

### D5 — Não-vazamento é AUDITADO em quatro camadas (o E2 desta POC é o teste existencial)

Método adversarial, todo automatizável — o análogo do "peso é o desempate" do poc-03:

1. **pcap** na interface de P durante as sessões completas de C1 e C2: critério **binário** — **0 pacotes** para qualquer destino ≠ porta do daemon Tor local (inclui DNS, NTP, qualquer dial direto).
2. **`lsof`/netstat em P:** **0 sockets de escuta** não-loopback durante toda a sessão (P se comporta como o cliente do ADR-0005).
3. **Logs de B e R** (logam o IP de toda conexão de entrada): todo IP visto nas conexões de P deve (a) ≠ IP real de P e (b) pertencer à **lista de exit nodes do consenso Tor** (conferível por script contra Onionoo/consenso). É a asserção mais forte: não só "não vi o IP", mas "só vi exits conhecidos". (No caminho onion, o critério vira "R não registra IP de origem discável" — conexão chega pelo onion service.)
4. **Dump do wire** do push (CBOR/req-resp): **0 ocorrências** de endereço de P em qualquer frame.

Uma célula que **funcione mas vaze** em qualquer camada é ❌ — funcionalidade não compra a asserção.

### D6 — rust-libp2p: QUIC OFF, swarm contido; o Transport SOCKS é o candidato a estourar o veto

O adapter libp2p precisa: **desligar QUIC** (UDP não passa no Tor), rotear TCP por SOCKS/onion (Transport customizado no facade rust — não vem pronto), e **conter tudo que disca sozinho** (identify pode vazar endereços observados; mDNS disca LAN; multiaddrs `/dns/` resolvem localmente). O pcap valida a contenção. Registrar honestamente: se o Transport SOCKS custar mais que o veto de 5 dias, ou se o swarm vazar no pcap sem contenção viável, a célula libp2p fecha ❌ com evidência — **resultado válido** (como o go saiu no poc-03). A Trama parte na frente por construção (superfície 100% do projeto).

### D7 — Limiares refixados para Tor (a latência degradada é o preço honesto)

Os limiares do poc-02/04 foram medidos em clearnet; o circuito Tor (6 hops no onion) adiciona centenas de ms a segundos. Refixar **a priori**:

| Métrica | Clearnet (poc-04) | Limiar Tor (poc-05) |
|---|---|---|
| Handshake Noise (frio, circuito novo) | < 1 s | **< 10 s** |
| Requisição quente (circuito estabelecido) | < 500 ms | **< 2 s** |
| Push do capítulo (768 KiB) | ~3 s | **< 60 s** |
| Lookup frio via Tor (C2) | ≤ 3 RTTs | **< 10 s** |
| Vazamento (pcap) | — | **0 pacotes** fora do Tor (binário) |
| Retoma pós-morte de circuito | — | recupera sem intervenção |
| Mudança no app leitor | 0 linhas | **0 linhas** |
| Vazamento de seam novo | 3 pts (poc-04) | **≤ 1 novo**, documentado |
| Veto de esforço | 5 dias/adapter | **5 dias/adapter** |

## Risks / Trade-offs

- **Vazamento por caminho não-óbvio** (NTP, telemetria da JVM, DNS pré-SOCKS, um dial direto de fallback do swarm) → o pcap binário pega qualquer um; a asserção é "0 pacotes", não "0 pacotes que eu lembrei de checar".
- **P na mesma máquina física do rig R/B** (o setup das POCs, `177.203.17.5`) → o tráfego sai pelo Tor e volta; o pcap ainda prova não-vazamento, mas o ideal é P em máquina/rede separada, ou captura isolada por processo. Registrar como fizeram com o `adb reverse` no poc-03/04.
- **Exit policy bloqueia a porta de R** → não é falha: é dado a favor da variante onion (dual-homed) ou porta 443. Rodar onion se o exit atritar.
- **Transporte Tor do rust-libp2p custoso/instável** → veto de 5 dias transforma estouro em conclusão; a célula fecha ❌ com evidência e o veredito registra "modo anônimo commita na Trama".
- **Timing/correlação com poucos nós** → trivial correlacionar P por horário numa rede de teste; limite do cenário e do Tor, não critério — registrado no modelo de ameaças.
- **Latência do onion (6 hops) estoura o limiar de push** → o limiar já foi afrouxado para 60 s a priori; se ainda estourar, é dado real sobre a UX do modo anônimo (publicar é assíncrono e raro, tolera segundos — registrar o julgamento).

## Migration Plan

Não aplicável (POC de código descartável, fora dos módulos de produto). A "migração" relevante é de **conhecimento**: se o veredito for positivo, a poc-05 informa (a) um ADR formalizando o Tor como alternativa de alcançabilidade **e** privacidade, (b) a superfície `push` do módulo de rede do Marco 2/4, e (c) o requisito de que o contrato do seam aceite endereços não-IP (strings opacas + filtro "discável por este backend") — custo zero agora, caro de retrofitar. Rollback = arquivar a change; nenhum artefato de produto tocado.

## Open Questions

- **Daemon Tor externo × biblioteca embutida** (arti/tor-rust) no lado do publicador? A POC usa daemon externo (mais simples, coerente com "Tor só em nós plenos"); embutir é decisão de produto do Marco 4.
- **Derivar o endereço onion v3 da identidade Ed25519 do nó** (ambos usam Ed25519) unifica identidade e alcançabilidade — vale um spike dentro do E1, ou fica como só-design?
- **Bootstrap via Tor** (ADR-0007 ganharia um canal `.onion`) — fora do escopo mínimo; registrar se o C2 tocar nisso.
- O push deve ser um método de `FullNode`/`Replicator` no seam ou um papel separado? Decidir no E0 conforme o TCK exigir.
