# Relatório da PoC — poc-02 (camada de rede própria)

> Artefato durável do poc-02. O código em `poc02/` é descartável; o que vale é este relatório.
> Change: [openspec/changes/archive/2026-07-03-poc-02](../openspec/changes/archive/2026-07-03-poc-02/proposal.md) · Design: [design.md](../openspec/changes/archive/2026-07-03-poc-02/design.md)
> Linha de base de comparação: [poc01-report.md](poc01-report.md) (nabu/jvm-libp2p, Marco 0).
>
> **Follow-up (poc-03):** esta stack própria venceu o **port JVM capenga** do libp2p (nabu). O
> [poc-03](../openspec/changes/archive/2026-07-03-poc-03/proposal.md) mede o libp2p **de referência** (go/rust) via
> bindings nativos para um benchmark justo. Dado central que ataca a vitória do poc-02 (0,96 MB de
> APK, Kotlin puro): o binding pesa **~6 MB/ABI (rust) a ~29 MB/ABI (go)** — go estoura o teto de
> 20 MB/ABI, rust não. Ver [poc03-report.md](./poc03-report.md).
>
> **Follow-up (poc-04):** a decisão de stack foi fechada pela terceira via — a stack deste
> relatório (batizada **Trama**) e o rust-libp2p rodaram atrás de um **seam neutro com
> backend trocável em build-time** (matriz E2E 8/8, dual-stack, veredito `própria →
> rust-libp2p condicional a gatilho`). Ver [poc04-report.md](./poc04-report.md).

**Status: CONCLUÍDO** (jul/2026). Todos os experimentos executados — incluindo dispositivo
físico (Moto g(30), API 31), sessão de 30 min de bateria/dados, e o E2E de descoberta fria
por **outra rede** (hotspot/dados móveis → endereço público, critério do Marco 0). Nenhum
veto de esforço foi aproximado. **Recomendação: implementação própria (Noise XX + RPC de
frames + membership/gossip) como stack de rede do Marco 2** — ver a seção final. Os limiares
abaixo foram fixados ANTES de qualquer medição.

## Limiares fixados a priori (design D5)

Definidos ANTES de qualquer medição. Ajustes posteriores exigem justificativa registrada aqui.

| Métrica | Cenário | Limiar |
|---|---|---|
| Bateria | Mesma sessão do poc-01: 30 min, lookups periódicos, Moto g(30) | **< 5%** |
| Dados móveis | Mesma sessão, tráfego do UID além do conteúdo | **< 20 MB** |
| Handshake | Primeira conexão, dispositivo físico, rede real | **< 1 s** |
| Reconexão | Reconexão subsequente ao mesmo nó | **< 500 ms** |
| Lookup frio (gossip) | Cliente resolve providers a partir só do bootstrap | **≤ 3 RTTs** |
| Delta de APK | Camada de rede própria completa (vs 12 MB do nabu) | **≤ 2 MB** |
| Veto de esforço | Cada variante do E1 (TLS; Noise) | **≤ 5 dias úteis** cada |

Referências do nabu (poc-01, medidas e publicadas): bateria ≈ 0,03% / dados 1,09 MB na sessão
de 30 min; APK debug 12 MB sem minify.

## Questões abertas a responder (do design)

| # | Questão | Resposta |
|---|---|---|
| Q1 | Certificado Ed25519 direto no JSSE/Android funciona, ou é preciso cert ECDSA + extensão com identidade assinada (esquema libp2p-tls)? | **Parcial (JVM respondida):** funciona no JSSE do JDK 21, mas SÓ com chaves dos providers do próprio JDK (o KeyManager seleciona por nome `"EdDSA"`; chaves BC reportam `"Ed25519"` → handshake_failure). Android não tem provider EdDSA de plataforma → **ECDSA + extensão é o caminho portátil** (implementado e testado). Confirmar no dispositivo (2.3). |
| Q2 | Overhead real de bcpkix vs Conscrypt no APK? | **Parcial:** bcpkix embarcado custa pouco — APK release+R8 com bcprov+bcpkix inteiros (provider preservado) = **0,96 MB total**. Conscrypt não foi necessário até aqui (dispositivo de teste é API 31; TLS 1.3 de plataforma). Pendente: confirmar minSdk 26–28 (2.3). |
| Q3 | Anúncio de provider no gossip: por obra ou por manifesto? | **Por obra.** `resolve(obraId)` é a única consulta; por manifesto multiplicaria os registros por nº de capítulos sem servir consulta nenhuma — e o E3 mediu que o custo do gossip é ∝ registros ativos (ver seção E3). |
| Q4 | kotlinx.serialization protobuf ou cbor para o wire? | **CBOR.** Medido: diferença de poucos bytes (72×66 B em requisição; 65.549×65.540 B em blob de 64 KiB — `@ByteString` é length-prefix cru); CBOR é autodescritivo e depurável sem schema. Protobuf ficou só no teste comparativo. |

## Setup (grupo 1)

- Módulo `poc02/net` (JVM, toolchain 21) + `poc02/android` (minSdk 26, AGP 9.0.1) criados,
  fora dos módulos de produto. Dependências (D1): `bcprov-jdk18on` 1.80, `bcpkix-jdk18on` 1.80
  (só E1a), `kotlinx-serialization` (cbor + protobuf) 1.9.0, `kotlinx-coroutines-core` 1.11.0.
  **Nenhum framework P2P.**
- `Manifest.kt` + testes portados do poc-01 sem mudança funcional (D8); identidade Ed25519
  (`NodeIdentity`) com suporte a identidade determinística por seed (técnica do E5 do poc-01).
- Transporte TCP: sockets bloqueantes do JDK, frames length-prefixed de 4 bytes, limite de
  4 MiB por frame; loopback JVM↔JVM testado (eco, fronteiras de frame, EOF limpo, guarda
  contra frame gigante).
- Observação (não é a medição do E5): APK debug do app mínimo com TODAS as deps do poc-02 já
  embarcadas (bcprov + bcpkix + serialization + coroutines), sem minify: **5,4 MB** — contra
  12 MB do app do poc-01 nas mesmas condições.

## Experimentos

### E1a — Canal seguro: TLS 1.3 (veto: 5 dias úteis)

**Parcial: lado JVM POSITIVO; medições em dispositivo pendentes (2.3/2.5).**

Implementado em `poc02/net` (`tls/TlsIdentity.kt` + `tls/TlsChannel.kt`): certificado
autoassinado ligado à identidade Ed25519, `X509ExtendedTrustManager` customizado que ignora
cadeia/CA e valida a prova de identidade (exceção no TrustManager aborta o handshake antes de
qualquer dado de aplicação), autenticação mútua (`needClientAuth`), TLS 1.3 fixado.

- **Q1 (parcial, JVM):** certificado **Ed25519 direto funciona no JSSE do JDK 21** — mas só
  com chaves convertidas pelos providers do próprio JDK (`KeyFactory "EdDSA"`/SunEC). Com as
  chaves via provider BC o handshake falha (`handshake_failure`): o KeyManager do JSSE
  seleciona certificado pelo nome de algoritmo `"EdDSA"` e as chaves BC reportam `"Ed25519"`.
  Consequência para Android: não existe provider EdDSA de plataforma → a estratégia portátil
  é **ECDSA P-256 + extensão com identidade assinada** (esquema do libp2p-tls), também
  implementada e testada. Confirmação no dispositivo pendente (task 2.3).
- **Handshake mútuo JVM↔JVM (2.2): OK** nas duas estratégias — cada lado extrai e autentica
  a identidade Ed25519 do par; sessão negocia TLSv1.3.
- **Impostor (2.4): OK** — servidor com canal tecnicamente válido mas identidade diferente da
  esperada é rejeitado durante o handshake (o handler do servidor nunca recebe a conexão);
  extensão transplantada para outra chave de canal (replay do binding) também é rejeitada,
  pois a assinatura cobre o SPKI da chave de canal.
- **Latência em loopback JVM (referência, NÃO é a métrica do D5):** handshake ≈ 4,7 ms;
  reconexão com session resumption do TLS 1.3 confirmada (`session.isValid`) via `SSLContext`
  persistente no discador.
- **Dispositivo físico (2.3/2.5) — Moto g(30), API 31, Wi-Fi → endereço público (hairpin):**
  TLS 1.3 negociado com sucesso (só `TLSv1.3` habilitado no socket); handshake frio
  **171–242 ms** (limiar < 1 s ✓); reconexão com session resumption **43–46 ms**
  (limiar < 500 ms ✓) — ~3× mais rápida que a reconexão Noise, confirmando a análise a
  priori do D2 sobre o padrão mobile abre-app→lê→fecha.
- **Dois achados de integração Android no caminho (2.3):**
  (a) o Android registra um provider de sistema chamado **"BC" castrado** (sem `EC`) que
  sombreia o bcprov embarcado — pinar `provider("BC")` quebra em runtime
  (`NoSuchAlgorithmException: EC`); a correção é resolver via plataforma (AndroidOpenSSL).
  (b) **Q1/Android confirmada no dispositivo:** cert Ed25519 direto NÃO é suportado
  (`InvalidKeySpecException` — não há KeyFactory EdDSA de plataforma) → ECDSA + extensão é
  o único caminho portátil.
- **Conscrypt (decisão da 2.3):** TLS 1.3 de plataforma existe só em API 29+; para minSdk
  26–28 seria Conscrypt (AAR ≈ +3–4 MB — maior que o app inteiro de 0,96 MB) ou fallback
  TLS 1.2 (stack divergente). Não embarcado na PoC; com Noise como canal a questão some.
- Dependências: nenhuma além do plano (bcpkix para gerar o X.509; JSSE é da plataforma).

### E1b — Canal seguro: Noise XX (veto: 5 dias úteis)

**Parcial: lado JVM POSITIVO, vetores oficiais 100%; medições em dispositivo pendentes (3.4).**

Implementado em `poc02/net` (`noise/Noise.kt` + `noise/NoiseChannel.kt`):
`Noise_XX_25519_ChaChaPoly_SHA256` (spec rev. 34) — CipherState/SymmetricState/HandshakeState,
HKDF de HMAC-SHA256, nonce de 64 bits LE com guarda de esgotamento, limite de 65535 B por
mensagem. Primitivas 100% BouncyCastle (X25519Agreement, ChaCha20Poly1305, SHA256Digest);
artesanal é só a orquestração. Frames de aplicação maiores que uma mensagem Noise são
fatiados/remontados pelo canal.

- **Vetores oficiais (3.2, bloqueante): PASSOU** — vetores `Noise_XX_25519_ChaChaPoly_SHA256`
  das suítes do cacophony e do snow (implementações independentes), cobrindo as 3 mensagens do
  handshake, o `handshake_hash` e as mensagens de transporte pós-split, byte a byte nas duas
  direções. Desbloqueia o uso da variante no E2/E4.
- **Identidade ligada (3.3): OK** — payload do handshake carrega
  `identityPub(32) || sig(identityPriv, "opentoons-noise-static:" || staticNoisePub)`
  (esquema do libp2p-noise). Impostor (identidade válida ≠ esperada) e assinatura de binding
  forjada são rejeitados no handshake — o servidor nunca recebe a 3ª mensagem, nenhum dado de
  aplicação flui. Ciphertext adulterado no transporte falha na decifra (AEAD).
- **Latência em loopback JVM (referência, NÃO é a métrica do D5):** handshake ≈ 3,2 ms;
  reconexão ≈ 1,6 ms — reconexão é handshake completo (XX não tem resumption; 0-RTT exigiria
  o padrão IK, fora do escopo).
- **Dispositivo físico (3.4) — Moto g(30), API 31, Wi-Fi → endereço público (hairpin):**
  handshake frio **190–320 ms** (limiar < 1 s ✓); reconexão **141–157 ms** (limiar
  < 500 ms ✓; sempre handshake completo). Funcionou de primeira no dispositivo — zero
  ajuste específico de Android (as primitivas BC independem de provider de plataforma).
- Dependências: zero novas (bcprov já estava; **não** precisa de bcpkix nem JSSE).
- LoC: Noise 281 + canal 129 = **410** (vs 285 do E1a TLS) — dentro da estimativa a priori
  de 400–600 do design D2.
- Riscos residuais (3.5): os vetores cobrem handshake e transporte, mas **nonces/limites são
  responsabilidade perpétua do projeto** (guarda de esgotamento implementada; rekey não);
  sem session resumption (reconexão = 1,5 RTT sempre; 0-RTT exigiria padrão IK); side
  channels dependem das primitivas BC, não auditadas pelo projeto.

### Decisão do canal (matriz D2) — **recomendação: Noise XX**

| Critério | E1a — TLS 1.3 | E1b — Noise XX |
|---|---|---|
| Esforço (veto 5 dias) | « 1 dia (dentro do veto) | « 1 dia (dentro do veto) |
| LoC | 285 (cert 142 + canal 143) | 410 (protocolo 281 + canal 129) |
| Dependências novas | bcpkix (geração X.509); Conscrypt se minSdk < 29 (+3–4 MB) | zero (bcprov já presente) |
| Rede de proteção | plataforma (JSSE); zero cripto artesanal | vetores oficiais 100% (cacophony + snow) |
| Ligação identidade↔canal | contorno: ECDSA + extensão assinada (Ed25519 direto: só JVM; **Android confirmado: não suporta**) | natural: estática assinada no payload |
| Handshake — dispositivo, rede móvel (Wi-Fi local) | **313 ms** (171–242 ms) ✓ | **319 ms** (190–320 ms) ✓ |
| Reconexão — dispositivo, rede móvel (Wi-Fi local) | **158–229 ms** com resumption (43–46 ms) ✓ | **273–312 ms** handshake completo (141–157 ms) ✓ |
| Throughput loopback (capítulo 768 KiB) | 48 MB/s | 103 MB/s |
| Impostor rejeitado antes de dados | SIM (TrustManager aborta handshake) | SIM (payload verificado na msg 2/3) |
| Android minSdk 26 | TLS 1.3 só API 29+; 26–28 → Conscrypt ou TLS 1.2 divergente | idêntico em 26+; funcionou de primeira no dispositivo |
| Surpresas de integração no experimento | **3** (nome EdDSA×Ed25519 no KeyManager; SSLException opaca; provider "BC" do sistema sombreando o bcprov) | **0** |
| Depuração | erros JSSE opacos | bytes 100% do projeto |
| Manutenção | patches da plataforma | responsabilidade perpétua do projeto (410 LoC, vetores como rede) |

**Racional da decisão (4.1):** o framework a priori do design D2 dizia — risco do TLS é *de
integração*, risco do Noise é *de correção criptográfica*. Os dados desempataram: o risco de
integração do TLS **se materializou 3 vezes** (mais a lacuna API 26–28, cujo remédio custa
mais MB que o app inteiro); o risco de correção do Noise **não se materializou** (vetores
oficiais 100%, dispositivo de primeira) e sua superfície de manutenção é pequena e cercada
por testes. Noise cobre minSdk 26 sem dependência de plataforma e é o caminho KMP limpo
(protocolo em commonMain). O TLS vence só em reconexão (45 ms × 150 ms — ambos folgados no
limiar de 500 ms) e em "cripto mantida pela plataforma", que fica registrado como o custo
assumido da escolha. **Canal do Marco 2: Noise XX** (o E4 já roda sobre ele); TLS 1.3 fica
documentado como plano B validado.

### E3 — Descoberta: membership+gossip × Kademlia enxuto (simulação)

**Veredito: POSITIVO — as duas variantes resolvem descoberta fria em todas as escalas;
recomendação: GOSSIP para o Marco 2, com gatilho de migração documentado abaixo.**

Implementado em `poc02/net/sim/` atrás da interface comum `resolve(obraId) → [providers]`
(`discovery/Discovery.kt`): E3a gossip (membership completo + anti-entropia com digests
separados p/ membership e providers + PEX; anúncio POR OBRA) e E3b Kademlia enxuto (k-buckets
k=20, walk iterativo α=3, provider records com expiry/republish), ambos incorporando as lições
dos 3 bugs do nabu do poc-01 (ADD_PROVIDER confirmado; pleno entra no router e cliente nunca;
sha256 consistente no espaço de chaves do walk).

Cenário: 20 obras × 3 réplicas (5×2 em n=10), TTL de provider 5 min, re-anúncio/republish
1 min nas DUAS variantes; churn de 20% (nunca publicadores) + mesmo número entrando frio;
janela de tráfego de 10 min em regime; tick = 10 s. Idealizações registradas: sem perda de
mensagem entre vivos, detecção de falha uniforme (30 s), tamanhos de wire fixos (48 B membro,
80 B provider, 8 B/entrada em listas de diff).

**Matriz medida (simulação; `SimRunnerKt`):**

| Variante | n | RTTs lookup frio | RTTs quente | Tráfego/nó/h (KB) | Memória/nó (KB) | Convergência pós-churn (s) |
|---|---|---|---|---|---|---|
| gossip | 10 | 2,0 | 1,0 | 202 | 1,3 | 10 |
| kademlia | 10 | 2,0 | 2,0 | 771 | 1,2 | 10 |
| gossip | 100 | 2,0 | 1,0 | 1.034 | 9,4 | 50 |
| kademlia | 100 | 2,0 | 2,0 | 1.931 | 3,6 | 10 |
| gossip | 1.000 | 2,0 | 1,0 | 1.144 | 51,6 | 60 |
| kademlia | 1.000 | 2,2 | 2,2 | 489 | 3,5 | 10 |
| gossip | 10.000 | 2,0 | 1,0 | 1.165 | 473,4 | 90 |
| kademlia | 10.000 | 2,9 | 2,9 | 360 | 3,6 | 10 |

Em ambas as variantes e todas as escalas o cliente terminou com **0 requisições de entrada e
0 registros armazenados** (ADR-0005 verificado estruturalmente e por contador).

**Leituras:**

- **Lookup:** gossip fica em 2 RTTs frio / 1 RTT quente constantes (limiar D5 de ≤ 3 RTTs
  folgado). Kademlia cresce com log n (2,9 RTTs em 10k) e **todo** lookup do cliente é frio —
  a tabela nasce vazia a cada abertura do app (ADR-0005), exatamente o custo de bateria que o
  design D3 antecipou.
- **Tráfego — o achado central:** o custo do gossip NÃO é dominado pelo membership (o full
  view de 10k nós custa 473 KB de memória e some do tráfego em regime); é dominado pela
  **epidemia de re-anúncios de providers**: cada refresh de expiry propaga para os n nós
  (O(n) de rede por registro por ciclo), enquanto no Kademlia toca só os K mais próximos
  (O(K) por registro). Por isso o gossip estabiliza em ~1,1 MB/h/nó com 60 registros e o
  Kademlia cai para ~360 KB/h em 10k. O tráfego do gossip escala com **obras × réplicas ×
  frequência de refresh**, não com n.
- **Convergência pós-churn:** gossip 10→90 s (propagação epidêmica dos joins/mortes);
  Kademlia ~10 s (o walk contorna mortos e o republish de 1 min recoloca os records).
- **Custo medido por registro no gossip:** ≈ 18 KB/h/nó por provider record com refresh de
  1 min (1.100 KB/h ÷ 60 registros). Extrapolação analítica: 1.000 registros ≈ 18 MB/h/nó;
  10.000 registros ≈ 180 MB/h/nó com estes parâmetros (mitigável linearmente com TTL/refresh
  maiores ou deltas por `seq` do publicador).

**Decisão e gatilho de migração (5.6):** recomendar **gossip** para o Marco 2 — 1 RTT quente,
descoberta fria em 2 RTTs, implementação e depuração simples (289 LoC vs 278 do Kademlia, mas
o Kademlia enxuto da PoC omite refinamentos que uma DHT real exige: LRU com ping, expiração
fina de buckets, proteção contra eclipse), papel do cliente natural e convergência suficiente.
**Gatilho objetivo de migração para DHT** (ou re-projeto do gossip para deltas por seq):
quando `obras × réplicas` ultrapassar **≈ 5.000 registros ativos** (≈ 90 MB/h/nó com refresh
de 1 min; ≈ 7,5 MB/h/nó já com refresh de 12 min), ou quando n de nós plenos passar de
~10.000 (memória e listas de diff de membership começam a pesar). Registrar no roadmap.

**Q3 respondida:** anúncio **por obra**. `resolve(obraId)` é a única consulta da descoberta;
anúncio por manifesto multiplicaria os registros por capítulos/obra sem servir a nenhuma
consulta (o manifesto vem por RPC do provider já descoberto). Com o custo por registro medido
acima, por manifesto seria proibitivo (50 caps/obra → 50× o tráfego de anúncio).

Esforço/LoC (5.6): harness+runner 236 LoC, gossip 289, Kademlia 278; ~meio dia de trabalho
com 1 bug real encontrado em cada variante durante o desenvolvimento (aritmética do
`bucketIndex` que descartava peers próximos; digest conjunto membership+providers que
inflava o tráfego do gossip 75×) — eco direto da lição do poc-01 sobre como é fácil errar
DHT/gossip.

### E2 — RPC + blocos sobre o canal vencedor

**Veredito: POSITIVO (JVM/loopback); lado a lado com o nabu pendente de dispositivo.**

Implementado em `poc02/net/rpc/` (`Rpc.kt` 141 LoC + `ChapterService.kt` 90 LoC), agnóstico
ao canal (roda sobre TLS E TAMBÉM sobre Noise — testado nos dois): frame =
`[8 B request-id][1 B tipo][corpo]`, respostas com bit de resposta chegam em qualquer ordem
e são correlacionadas pelo id. Sem muxer, sem bitswap (design D4).

- **Concorrência numa conexão (6.1): OK** — teste força respostas em ordem INVERSA às
  requisições; todas correlacionam corretamente; erro de handler vira `RpcException` no
  chamador sem derrubar a conexão.
- **Q4 respondida — CBOR.** Medição direta: requisição pequena 72 B (CBOR) vs 66 B (proto);
  blob de 64 KiB: 65.549 vs 65.540 B (`@ByteString` do CBOR = length-prefix cru). Diferença
  desprezível nos corpos dominados por bytes de bloco; CBOR é autodescritivo (depurável com
  dump hex, sem schema). Irrelevante para as conclusões, como o design previu.
- **Download verificado (6.2): OK** — get-manifest + get-blocks (blocos requisitados TODOS em
  paralelo na mesma conexão), verificação da assinatura Ed25519 do manifesto (formato do
  poc-01, D8) + hash sha256 de cada bloco, capítulo de 3 blocos × 256 KiB reconstruído.
  Bloco adulterado e manifesto assinado por outra chave são rejeitados
  (`VerificationException`) — o mecanismo do cenário de rejeição do E4.
- **Throughput em loopback (6.3):** capítulo de 768 KiB — TCP puro 316 MB/s (baseline),
  Noise 103 MB/s, TLS (JSSE) 48 MB/s. Em loopback o custo é 100% CPU de cifra; em rede real
  o link domina — os números do dispositivo saem no E4/E5. **Nota de comparabilidade:** o
  relatório do poc-01 não publicou throughput do nabu/bitswap; o lado a lado exige re-medição
  do nabu no dispositivo (fazer junto com o E5).
- **Head-of-line blocking (D4): NÃO apareceu** — bloco de 1 KiB requisitado atrás de um frame
  de 4 MiB na mesma conexão: 0,77 ms vs 0,87 ms sozinho (loopback). A janela real de HOL é o
  tempo de transmissão do frame grande no link; o canal Noise já fatia em mensagens de 64 KiB
  mas o envio de um frame é atômico — se aparecer em rede real (E4), a mitigação é intercalar
  chunks de frames concorrentes. Conforme o design, nada foi mitigado antecipadamente.

### E4 — E2E do Marco 0 com a stack própria

**Parcial: mecânica completa PROVADA em localhost; caminho público real pendente (7.1/7.2).**

Implementado em `poc02/net/node/` (`FullNode.kt` + `Main.kt`): nó pleno REAL da descoberta
gossip (membership + PEX + anúncios com TTL sobre o RPC do E2, malha por dials de SAÍDA
rediscada — lição do E5 do poc-01) sobre o canal Noise; cliente que só faz conexões de
saída, sem HELLO, sem estado persistente (ADR-0005).

- **E2E local (teste `E2eLocalTest`): OK** — bootstrap + publicador formam malha; o cliente,
  conhecendo SÓ o bootstrap e o obraId, descobre o publicador (nunca informado, 2 RTTs),
  baixa manifesto + 3 blocos, verifica assinatura e reconstrói o capítulo.
- **Caminho público real (7.1/7.2): EXECUTADO** — bootstrap (`:4100`) e publicador (`:4101`)
  no ar anunciando o endereço público `177.203.17.5` (mesmo port forwarding do poc-01;
  malha interna via LAN — o registro de provider carrega o endereço público).
  (a) Alcançabilidade externa comprovada: check-host.net conectou no TCP 4100 e 4101 a
  partir de **6 nós em 6 países** (159–301 ms) — mesmo método do poc-01.
  (b) App no Moto g(30) com descoberta FRIA pelo endereço público: PEX+RESOLVE no bootstrap
  (2 RTTs) → aprende o publicador `177.203.17.5:4101` (nunca informado) → disca → baixa →
  verifica assinatura → reconstrói as 3 páginas em **1.164 ms**. Log: `DESCOBERTA OK →
  ASSINATURA OK → CAPÍTULO RECONSTRUÍDO → REJEIÇÃO OK (bloco) → REJEIÇÃO OK (manifesto) →
  E4 OK`.
- **Rejeição de adulterado no dispositivo (7.3): OK** — bloco com hash divergente e
  manifesto assinado por outra chave rejeitados e reportados no app.
- **Critério estrito (7.2) — outra rede: PASSOU.** O aparelho (sem SIM) foi conectado ao
  hotspot de um iPhone (dados móveis de outra operadora, rede `172.20.10.x`, atrás de NAT
  da operadora): descoberta fria pelo endereço público em **2 RTTs**, publicador discado em
  `177.203.17.5:4101`, capítulo baixado, verificado e reconstruído em **1.004 ms**, ambas
  as rejeições OK — exatamente o critério de conclusão do Marco 0, fechado pela stack
  própria pelo caminho público real. (Antes disso o mesmo cenário havia passado via NAT
  hairpin da LAN, com alcançabilidade externa provada por 6 nós em 6 países.)

### E5 — Medições comparativas (sessão de 30 min, APK, LoC)

**Veredito: POSITIVO — todos os limiares passam com folga de 1–2 ordens de grandeza.**

**Sessão de 30 min (8.1) — Moto g(30), API 31, mesmo roteiro do poc-01** (tela ligada,
`battery unplug` lógico + `batterystats --reset`, lookups frios a cada 33 s via endereço
público, tráfego por UID):

| Métrica | poc-02 (própria) | poc-01 (nabu) | Limiar | Passa? |
|---|---|---|---|---|
| Lookups na sessão | 54 (0 falhas, todos 2 RTTs) | 55 | — | — |
| Bateria da stack (batterystats, 31 min) | **≈ 0,59 mAh** (cpu 0,011 + wifi 0,58) ≈ **0,012%** da bateria de 5.007 mAh; UID total 61,9 mAh dos quais 61,3 são TELA | ≈ 1,55 mAh ≈ 0,03% | < 5% | **SIM** |
| Dados além do conteúdo | **0,13 MB** (rx 0,07 + tx 0,06) | 1,09 MB | < 20 MB | **SIM** |

Leitura: a stack própria custou **~8× menos dados** e **~2,6× menos bateria** que o nabu na
mesma sessão — 54 lookups de descoberta fria custam 130 KB porque cada um é
PEX + RESOLVE (2 RTTs, ~2,4 KB), sem walk de DHT nem manutenção de routing table. Como no
poc-01, o consumo dominante do UID é a tela, não a rede; medição de PoC com USB conectado e
unplug lógico.

| Métrica | poc-02 (própria) | poc-01 (nabu) | Limiar |
|---|---|---|---|
| APK debug, sem minify | **4,3 MB** | 12 MB | — |
| APK release + R8 (app completo) | **0,96 MB** | não medido no poc-01 | delta ≤ 2 MB → **PASSA por construção** (o app inteiro < 1 MB) |
| LoC da camada de rede | **1.617** (core 204, TCP 105, TLS 285, Noise 410, RPC 240, descoberta+nó 373) | — (nabu+jvm-libp2p+Netty embarcados) | — |
| Dependências de rede | **3** (bcprov, bcpkix, kotlinx-serialization-cbor) + coroutines | nabu → jvm-libp2p, Netty, noise-java, dnsjava, BC×2… | — |

Notas da medição de APK: R8 com keep do provider BC inteiro (os SPIs são registrados por
string) e exclusão dos recursos pós-quânticos do bcprov (1,2 MB de `lowmc*.bin` que nada
referencia). O protobuf saiu do APK (só o teste do Q4 usa; CBOR é o wire). O APK debug de
5,5 MB caiu para 4,3 MB só com a exclusão dos recursos PQC.

**Latências em rede real (8.2)** — dispositivo em outra rede (hotspot/dados móveis) →
endereço público:

| Métrica | Noise XX | TLS 1.3 | Limiar | Passa? |
|---|---|---|---|---|
| Handshake (frio) | 319 ms | 313 ms | < 1 s | **SIM** |
| Reconexão | 273–312 ms (handshake completo) | 158–229 ms (resumption) | < 500 ms | **SIM** |
| Lookup frio (gossip) | 2 RTTs | — | ≤ 3 RTTs | **SIM** |

(Na rede Wi-Fi local via endereço público os números foram: handshake 171–320 ms, reconexão
43–157 ms.) Em RTT de rede móvel a vantagem de resumption do TLS encurta para ~1 RTT
(~130 ms) — relevante mas dentro do limiar nas duas variantes.

Throughput lado a lado com o nabu segue sem número (o poc-01 não o publicou; exigiria
re-medição do nabu — registrado como lacuna assumida). Referência da stack própria: capítulo
de 768 KiB baixado e verificado em ~1,0–1,2 s de ponta a ponta (inclui handshake) em rede
móvel e Wi-Fi.

## Esforço real por experimento

Toda a parte JVM/simulação/código foi implementada em **1 sessão de trabalho** (jul/2026) —
ordens de magnitude dentro dos vetos. Registro por experimento:

| Experimento | Esforço | LoC (main) | Observações |
|---|---|---|---|
| Setup (módulos, manifesto, TCP, limiares) | ~1 h | 309 + 105 | testes verdes de primeira |
| E1a (TLS) | ~2 h | 285 | veto 5 dias: FOLGADO. 2 iterações de bug (nomes de algoritmo EdDSA×Ed25519; SSLException genérica) |
| E1b (Noise) | ~2 h | 410 | veto 5 dias: FOLGADO. Vetores oficiais passaram de primeira |
| E3 (gossip + Kademlia + harness) | ~3 h | 803 (sim) | 2 bugs reais no caminho (bucketIndex; digest conjunto) — ver seção E3 |
| E2 (RPC + capítulo) | ~1,5 h | 240 | 1 bug de teste (equals de ByteArray) |
| E4 código (nó pleno real + cliente + app) | ~1,5 h | 373 + app 180 | E2E local verde; caminho público executado no mesmo dia |
| Rodadas em dispositivo (E1/E4/E5) | ~2 h | — | 1 bug real de Android achado e corrigido (provider "BC" do sistema) |

Nota de honestidade: "1 sessão" reflete implementação assistida; o dado comparável ao
processo do poc-01 (dias de calendário com depuração de upstream) é que NENHUM veto foi
sequer aproximado e nenhum bug de terceiro bloqueou o caminho — os 4 bugs encontrados eram
todos do próprio código da PoC e foram corrigidos em minutos, não dias.

## Recomendação de stack para o Marco 2

**Recomendação: implementação própria** (Noise XX + RPC de frames + membership/gossip),
substituindo o nabu/jvm-libp2p. Todos os experimentos fecharam positivos, nenhum veto de
esforço foi sequer aproximado, e a comparação lado a lado favoreceu a stack própria em
todas as métricas medidas:

| Dimensão | Própria (poc-02) | nabu (poc-01) |
|---|---|---|
| Critério E2E do Marco 0 | fecha (descoberta fria pública, verificação, rejeição) | fecha (com 3 workarounds de bugs upstream) |
| Bateria (sessão 30 min) | ≈ 0,012% | ≈ 0,03% |
| Dados (sessão 30 min) | 0,13 MB | 1,09 MB |
| Handshake / reconexão (dispositivo) | 190–320 ms / 141–157 ms (Noise) | não medido isoladamente |
| APK debug | 4,3 MB (release+R8: 0,96 MB) | 12 MB |
| Código sob controle do projeto | 1.617 LoC, 3 deps | nabu + jvm-libp2p + Netty + noise-java + dnsjava, upstream sem builds utilizáveis |
| Bugs de terceiros no caminho | 0 | 4 diagnosticados (workarounds em todas as camadas) |
| Lookup do cliente | 2 RTTs frio / 1 quente, constante até 10k nós (sim.) | walk DHT com tabela fria a cada abertura |

**Componentes recomendados:** canal **Noise XX** (matriz D2 — TLS 1.3 fica como plano B
validado); descoberta **membership + gossip** com anúncio por obra (matriz D3 — gatilho de
migração p/ DHT: ≈ 5.000 registros ativos de provider ou ~10.000 nós plenos, registrado no
roadmap); RPC de frames com request-id em CBOR, sem muxer e sem bitswap (D4 confirmado:
head-of-line blocking não observado).

**Consequência assumida (não mensurável por experimento):** ao abandonar o TLS da plataforma
e o framework, o projeto assume **manutenção perpétua de código de segurança** — os 410 LoC
do Noise (nonces, estados, binding de identidade) e o TrustManager/certificados do plano B.
Mitigações permanentes: vetores oficiais no CI como rede de regressão, superfície mínima
(uma cifra, um padrão de handshake), primitivas delegadas ao BouncyCastle. Este custo foi
pesado contra o custo observado da alternativa: manter workarounds sobre um upstream sem
releases utilizáveis e depurar bugs de terceiros em 4 camadas (poc-01).

**Ressalvas registradas:** (a) throughput lado a lado com o nabu segue sem número (o poc-01
não o publicou; referência própria: capítulo de 768 KiB em ~1,0–1,2 s ponta a ponta);
(b) a simulação do E3 decide a comparação, não a viabilidade — a viabilidade veio do caminho
real do E4, executado inclusive de outra rede (hotspot/dados móveis).
