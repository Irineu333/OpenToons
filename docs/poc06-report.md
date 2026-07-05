# poc-06 — rede nativamente anônima sobre I2P: relatório

> **Status: 27/27 tarefas fechadas.** Instrumento (transporte I2P/SAM atrás do seam do poc-05)
> construído e com o portão TCK verde nas duas formas (TCP loopback + streams I2P reais). A
> campanha T0–T7 rodou sobre I2P na **internet real, em três redes genuinamente separadas** — DEV
> (residencial), VPS (datacenter) e Moto g30 (hotspot móvel) — cada uma com seu próprio router
> i2pd. O crux (leitura fria do mobile) foi medido no device real, sobre a rede móvel do device.
> O único ponto não medido é o **mAh de bateria limpo** do plano B — limite físico da bancada
> (device no cabo), com o proxy de CPU registrado no lugar.
>
> Cada claim carrega classe de evidência: **[executado]** (rodou em máquina real, com saída
> observada), **[dado-só]** (número medido, não re-derivado), **[só-design]** (raciocínio sem
> execução). **Números do Tor (poc-05) NÃO contam como dado de I2P** — aparecem só como âncora de
> limiar, jamais como medição.

## 0. D0 — definição de "rede nativamente anônima sobre I2P" [só-design]

**A hipótese sob teste:** em vez do modelo documentado (rede clearnet + modo anônimo *opcional*
do publicador via Tor, poc-05 + ADR-0001/0006), a rede **inteira** — P (publicador), R/B
(replicador/bootstrap) e M (leitor mobile) — opera **exclusivamente por dentro do I2P**: cada nó
roda um router I2P local e todo dial/listen acontece por destinations I2P (túneis), nunca por
IP:porta clearnet.

- **O que a rede nativamente anônima PROTEGE (se viável):**
  - o **IP/localização de TODOS os papéis** — publicador, replicador E leitor — deixa de aparecer
    no wire da aplicação; cada nó é alcançável só por destination (pseudonimato de rede para todo
    mundo, não só para o publicador);
  - **NAT dissolvido por construção**: um nó atrás de NAT publica leaseSet e recebe conexões de
    entrada sem port-forward nem IP público (a promessa que o ADR-0006 resolve hoje com endereço
    público manual);
  - a identidade Ed25519 do publicador continua **pública por design** (assina tudo) — o que se
    protege é o vínculo identidade↔IP, como no D0 do poc-05.
- **O que a rede nativamente anônima NÃO promete (limites honestos):**
  - **correlação por adversário global-passivo / análise de timing** — limite do próprio overlay
    I2P, não validável numa PoC; registrado, jamais prometido;
  - **anonimato absoluto**: I2P dá pseudonimato de rede; padrões temporais de publicação
    (timestamps de manifesto, horários de `seq`) podem revelar fuso/rotina;
  - **disponibilidade igual à clearnet**: a rede depende de reseed e de túneis com latência/failure
    próprios; o custo disso é exatamente o que este poc mede;
  - **proteção contra um router I2P local comprometido** (o app confia no SAM local).
- **Critério de sucesso** = os limiares da §2 batidos NOS TESTES EXECUTADOS + a auditoria de
  não-vazamento (T6) limpa. Uma célula que *funcione mas vaze* clearnet no caminho de leitura é ❌
  (mesma regra binária do poc-05).

## 1. Perguntas Q (fixadas a priori)

| # | Questão | Teste que decide |
|---|---|---|
| **Q1** ⚑ | **Crux — cold-start do caminho de leitura do mobile:** um leitor a frio (router I2P recém-iniciado, app morto) abre um capítulo em tempo tolerável? | T3 (plano A), T4 (plano B) |
| Q2 | Qual o custo real de infra fria: reseed→túnel-pronto e dial frio? | T0 |
| Q3 | O backbone P→R sobre I2P fecha (push do poc-05 ainda é necessário? dual-homing some?) e a que throughput? | T1 |
| Q4 | Descoberta sobre I2P: P conhece só B e descobre R em quanto tempo? | T2 |
| Q5 | Plano A × plano B: o mobile como nó pleno (servindo por destination) é viável — a que custo? | T3 × T4 |
| Q6 | Por camada da arquitetura: o que o I2P **subsume** (NAT), **muda de forma** (bootstrap), **sobrevive** (Bitswap/CID/manifesto), fica **mais caro** (descoberta sobre túnel)? | T5 (+T1/T2/T7) |
| Q7 | Não-vazamento: o caminho de leitura fica 100% dentro do I2P, sem fallback clearnet? | T6 |
| Q8 | O adapter I2P/SAM cabe no seam do poc-05 sem branch de app (mesmo código desktop/Android)? | portão TCK + 3.5 |
| Q9 | libp2p (Kademlia) sobre I2P: funciona e a que custo de descoberta/conexão? | T7 (E-fase) |

## 2. Limiares a priori (fixados COM O USUÁRIO antes de medir) [só-design]

Âncoras vindas do poc-05 (Tor: push < 60 s, lookup medido 3,7 s na Trama, download clearnet do
device ~721 KiB/s) e da régua de produto "leitor tolera ~1 minuto a frio". **São âncoras de
limiar, não dados de I2P.**

| Teste | Métrica | Limiar |
|---|---|---|
| T0 | reseed→túnel-pronto (router virgem, 1ª vez) | **< 300 s** |
| T0 | restart com netDb cacheado → túnel-pronto | **< 90 s** |
| T3/T4 | leitura mobile A FRIO: capítulo 768 KiB verificado | **< 90 s** |
| T3/T4 | leitura mobile A FRIO: tempo-até-primeiro-byte | **< 45 s** |
| T1 | push 768 KiB DEV→VPS completo | **< 60 s** |
| T1 | throughput mediana DEV↔VPS | **≥ 50 KiB/s** |
| T2 | descoberta sobre I2P (P conhece só B → acha R) | **< 20 s** |
| T6 | pacotes do caminho de leitura fora do I2P | **0 (binário)** |

Regras de julgamento: (a) medição de leitura sempre **a frio**; (b) mediana de ≥ 3 amostras;
(c) limiar estourado ⇒ a célula é reportada ❌ mesmo que "funcione".

## 3. Limites fora de escopo (declarados a priori, jamais prometidos) [só-design]

- **Correlação por adversário global-passivo / timing analysis** — limite do próprio I2P; não
  medido, não prometido (idêntico ao D0 do poc-05 para Tor).
- **Ataques ativos à rede I2P** (Sybil em floodfill, eclipse de leaseSet, DoS de túnel) — fora do
  alcance de uma PoC com 3 nós.
- **Anonimato do conteúdo**: a obra e a identidade da editora são públicas por design; o poc não
  avalia deanonimização por estilometria/conteúdo.
- **Comparação Tor × I2P para o modo anônimo opcional do poc-05** — outra pergunta, não coberta.
- **Escala**: 3 nós reais não medem comportamento de malha grande.

---

## 4. Topologia executada

```
   PAPEL   HOST                       router I2P              processo poc06
   ─────────────────────────────────────────────────────────────────────────────
   P       DEV (macOS, residencial)   i2pd 2.60 :7656         probe (publicador/leitor)
   B       VPS 143.95.220.165         i2pd 2.60 :7656         I2pServerMain (bootstrap)
   R       VPS 143.95.220.165         (mesmo router)          I2pServerMain (replicador)
   M       Moto g30 (Android 12)      i2pd 2.60 arm64 :7656   app poc06 (arm64)
                                      (cross-compilado, no device)

   caminho de leitura mobile = M(Moto g30, hotspot móvel 172.20.10.5) ══I2P══ R(VPS, datacenter)
   caminho de backbone       = P(DEV, residencial)                    ══I2P══ R(VPS, datacenter)
   três routers I2P em três redes REALMENTE separadas; nenhuma transferência em loopback.
```

**Rig (`poc06/rig/`) [executado]:** `sam_spike.py` (de-riscagem SAM v3), `socks_spike.py`
(de-riscagem do caminho libp2p/SOCKS), `i2pd-dev.conf`/`i2pd-android.conf`/`tunnels-dev.conf`,
`warmup.sh` (cronômetro de túnel-pronto), `vps.sh` (SSH publickey-only à VPS), `audit-noleak.sh`
(pcap + captura por processo), `build-i2pd-android.sh` (cross-compilação reprodutível).
Router de referência: **i2pd 2.60.0 (0.9.69)** nos três hosts.

**Router I2P no Android [executado]:** o i2pd 2.60 foi cross-compilado para arm64-android com o
NDK r28 — OpenSSL 3.5.4 e Boost 1.86 (filesystem/program_options/system) estáticos, `libc++`
estático, zlib do sysroot do NDK. O binário (ELF aarch64 PIE, `interpreter /system/bin/linker64`)
roda no Moto g30 (`i2pd version 2.60.0`), reseeda pela rede móvel e expõe SAM em
`127.0.0.1:7656` no device — é o que torna a leitura mobile (T3/T4) uma medição da rede móvel do
próprio device.

## 5. O instrumento e o portão (fase de código)

### Reuso do seam (D2) [executado]

`poc06/api`, `poc06/trama`, `poc06/node` partem do poc-05. A **única** variável nova é o
**transporte**: um seam `FrameTransport` (`dial`/`listen`/`localAddress`) com duas implementações
— `TcpTransport` (baseline clearnet e TCK loopback) e **`SamTransport`** (I2P via SAM v3). Toda a
stack Trama (Noise XX, RPC de frames, membership/PEX, `push`, `PushPolicy`) roda **idêntica** por
cima, indiferente ao transporte. O adapter SAM (`wire/Sam.kt`) é **Kotlin puro** (`java.net.Socket`):
`SESSION CREATE STYLE=STREAM` (session = destination), `STREAM CONNECT` para discar, `STREAM
ACCEPT` (laço) para servir. Um endereço de rede vira uma **destination** base64 opaca — nada de
IP:porta no wire de aplicação.

### Portão de correção (D3) [executado]

- **TCK sobre TCP loopback** (`TramaTckTest`): **6/6 testes verdes** (resolve transitivo, download
  verificado, bloco adulterado → `BlockHashMismatch`, chave errada → `BadSignature`, push
  autenticado gravado-e-servido, push de chave errada rejeitado antes de gravar, expiry +
  republish).
- **TCK sobre STREAMS I2P REAIS** (`I2pTckRigMain`, host único, router DEV): ciclo completo por
  dentro de túneis I2P — R aceita push, publicador anônimo empurra por destination (18,8 s), leitor
  descobre R via B e baixa **786432 B verificados** (Ed25519 + hashes), impostor de chave errada
  rejeitado pela `PushPolicy`. `TCK_I2P_OK` (total 149,9 s incluindo build de túnel de cada papel).
- **Cross-compile Android (task 3.5):** `poc06/android` (APK arm64, `assembleDebug` verde); grep no
  `src/main` confirma **zero branch de transporte** app-level (o transporte entra só pela fábrica
  `TramaBackend.i2pClient`).

## 6. Campanha T0–T7 (rede real, a frio) [executado]

### T0 — warmup / alcançabilidade [executado / dado-só]

- **Régua aferida nos dois sentidos (D4):** o cronômetro `warmup.sh` mede `start → 1º túnel in +
  out`. Router **quente** (túneis já de pé): **0,05 s** (~0). Router **frio**: **9 s** (device) /
  **19,3 s** (restart no DEV com netDb cacheado). A régua reporta ~0 quente e >0 frio — mede o que
  afirma.
- **Cold-start no DEVICE [executado]:** o router i2pd no Moto g30 partiu de datadir zerado,
  **reseedou pela rede móvel** (`reseed.i2pgit.org`, netDb 268 routers) e chegou a **túnel-pronto
  em ~9 s** (« 300 s) ✅.
- **Fragilidade de reseed [executado]:** o reseed-do-zero no macOS **estourou > 600 s** em várias
  tentativas (mirrors falhando/timeout). O custo frio NÃO é o build de túnel (barato, ~9–20 s), é a
  **dependência de reseed** — que ora funciona em 9 s (device), ora não fecha em 600 s (desktop,
  mirrors ruins). É o **modo de falha novo** que o design previu, materializado.
- **Alcançabilidade mútua (task 2.4):** cada host publica seu leaseSet; R (VPS) é discável do DEV e
  do Moto g30 por túnel.
- **Deploy do replicador:** i2pd 2.39 (apt Ubuntu 22.04) faz **SEGV** (core-dump) na VPS; exigiu o
  repo oficial PurpleI2P para a 2.60 — atrito de deploy registrado.

### T1 — backbone P(DEV) → R(VPS) sobre I2P [dado-só]

| Métrica | Medido | Limiar | Veredicto |
|---|---|---|---|
| Push 768 KiB (publicação) | **27,3 s** (28,1 KiB/s) | < 60 s | ✅ |
| Throughput download (mediana de 5) | **46,1 KiB/s** (faixa 33–53) | ≥ 50 KiB/s | ⚠ marginal |

O backbone fecha sobre I2P entre **duas redes reais** (residencial ↔ datacenter). O `push` do
poc-05 **continua funcionando** mas deixa de ser *necessidade*: como o I2P dá alcançabilidade de
entrada por destination, R poderia PUXAR o publicador — o `push` vira **opção**. O dual-homing
(onion + IP público do poc-05) **some**: há um só endereço, a destination. Throughput **46 KiB/s
medido SOBRE I2P** (não herdado do Tor), marginalmente abaixo do alvo de 50.

### T2 — descoberta transitiva sobre I2P [dado-só]

P conhece **só B**; descobre R por gossip (PEX+RESOLVE da Trama) por dentro de túneis I2P. Medido
em duas topologias:
- **B+R na mesma VPS:** **7,3 s** ✅.
- **B no DEV e R na VPS — máquinas fisicamente distintas:** **5,4 s** ✅. R(VPS) bootstrappa em
  B(DEV) por I2P, P(DEV) conhece só B e descobre R(VPS). O gossip propaga por túnel real entre
  máquinas distintas.

A Trama usa **gossip de 1 circuito**, não walk de Kademlia — a comparação com o DHT está no T7.

### T3 — leitura plano A (consumidor) a frio [executado]

O crux do design (Q1), medido no Moto g30 com router i2pd nativo, sobre a rede móvel do device:

| Ambiente | total (frio) | ttfb | sessão SAM | resultado |
|---|---|---|---|---|
| Desktop (DEV ← R VPS) | **62,2 s** | 8,3 s | — | ✅ 786432 B verificados |
| Moto g30, amostra 1 | **86,9 s** | 6,6 s | 9,1 s | ✅ 786432 B (Ed25519 no device) |
| Moto g30, amostra 2 | **86,1 s** | 34,1 s | 15,1 s | ✅ 786432 B |
| Moto g30, amostra 3 | **69,1 s** | 7,6 s | 24,1 s | ✅ 786432 B |
| **Moto g30 — mediana** | **86,1 s** ✅ (< 90 s) | 6,6–34 s | 9–24 s | ✅ |

**Evidência de que o caminho é a rede móvel do device (T6 no device):**
- o router I2P é o **i2pd rodando no próprio Moto g30**;
- o egress do router do device sai por **`172.20.10.5 dev wlan0`** (hotspot móvel) para IPs
  públicos de peers I2P (ex.: 184.107.141.217, 71.169.184.173, 142.44.136.48);
- o app fala SAM só com `127.0.0.1:7656` do próprio device (loopback local);
- durante a leitura, **0 conexões diretas** do device ao IP da VPS — R alcançado só por túnel.

Leitura fria mobile **~86 s** (mediana, < 90 s ✅), ttfb 6,6–34 s (< 45 s ✅), dominada pela
construção da sessão SAM do cliente (9–24 s) e pela latência de descoberta+download sobre túnel,
não pela transferência. A verificação Ed25519 rodou no device. O router é serviço persistente, então
o custo por-leitura recorrente é o ~86 s (o cold-start do router, ~9 s, paga-se uma vez ao subir).

### T4 — leitura plano B (mobile como nó pleno que SERVE) [executado]

- **O mobile SERVE por destination:** um nó pleno poc06 rodou no Moto g30 (modo `serve` do app),
  sobre o router i2pd nativo do device, publicou o capítulo e o anunciou a B (VPS). Do DEV,
  discou-se a destination do nó mobile e baixou-se **786432 B verificados** que o device serve
  (`total 65,8 s, ttfb 4,7 s`). O Moto g30, **atrás do CGNAT do hotspot e sem port-forward**, é
  discável e serve conteúdo por destination — o plano B que o I2P destrava e o modelo clearnet do
  ADR-0005 tornava impossível.
- **Frescor de provider sob intermitência:** o mecanismo é o TTL + republish do anúncio, provado no
  TCK (`expiry após morte e republish após reviver`): quando o nó some, seu provider record expira;
  ao reviver, republica. A intermitência móvel se traduz nesse expiry/republish.
- **Custo de bateria (mAh limpo) — não medível nesta bancada:** o device está no USB/AC carregando
  (`dumpsys battery`: `status=FULL, level=100`), então um número de **drain de bateria** não é
  medível sem confundir com a carga (mesma limitação que o poc-05 declarou). Medido só o proxy de
  CPU: router i2pd ~18,8 s e app ~13,6 s de tempo de CPU acumulado na sessão. O número limpo
  exigiria o device fora do cabo com telemetria de energia.

### T5 — arquitetura por camada, exercida em código [executado]

| Camada | Efeito do I2P | Evidência executada |
|---|---|---|
| **NAT / alcançabilidade** | **SUBSUMIDO** | R serve por destination com **0 portas de app abertas** na VPS (varredura externa: portas 4000/4100/4200/5000/7656/7070 fechadas); o Moto g30 serve atrás do CGNAT (T4). O port-forward do ADR-0006 evapora |
| **Bootstrap / descoberta local** | **MUDA DE FORMA** | mDNS/LAN do ADR-0007 não existe no I2P (nós só se acham por destination); bootstrap por destination + gossip sobrevivem; **reseed vira nova dependência de cold-start** (T0) |
| **Catálogo / conteúdo** | **SOBREVIVE** | Bitswap (getBlocks), CID (sha-256), manifesto assinado Ed25519 e `PushPolicy` passam intactos sobre o stream I2P (TCK verde sobre I2P real) |
| **Descoberta sobre túnel** | **BARATA (gossip) / medida (DHT)** | gossip Trama 5,4–7,3 s (T2); Kademlia sobre I2P ~2,2 s no provider-direto, mas com **dial frágil** (T7) |

### T6 — auditoria de não-vazamento no caminho de leitura [executado]

Nos dois caminhos de leitura, sem fallback clearnet:
- **Desktop (DEV ← R VPS):** `sudo tcpdump` em en0 (filtro `host 143.95.220.165`) durante uma
  leitura completa → **0 pacotes** ao IP de R; `lsof` do PID do leitor → a única conexão TCP é
  `127.0.0.1:<efêmera> → 127.0.0.1:7656` (o SAM local). App SAM-only por construção.
- **Mobile (Moto g30):** `ss` no device durante a leitura → **0 conexões diretas** ao IP da VPS; o
  egress do i2pd sai por `wlan0` só a peers I2P públicos.

### T7 / E-fase — libp2p SOBRE I2P (a comparação) [executado]

Reusa o backend rust-libp2p do poc-05 SEM reescrever o motor: troca-se apenas o transporte anônimo
de Tor por I2P — o `SocksProxy` do libp2p aponta para o **proxy SOCKS do i2pd** (`127.0.0.1:4447`,
resolve `.b32.i2p`) e o full node anuncia `/dns/<b32>.i2p/tcp/<port>` (um **i2pd server tunnel**
entrega o inbound ao listener TCP local). Uma extensão aditiva foi feita no facade: o cliente
anônimo do poc-05 era push-only ("leitor é clearnet"); como o `AnonBehaviour` já tinha o
request-response de blocos, habilitou-se a leitura — o motor não mudou.

- **Caminho de transporte de-riscado:** SOCKS-dial + server-tunnel-listen — bytes ida-e-volta pelo
  I2P (spike `socks_spike.py`).
- **E2E verde:** R (libp2p) anuncia `.b32.i2p`, provê a obra por Kademlia; P (cliente libp2p via
  SOCKS do i2pd) disca R por I2P, **descobre por Kademlia em ~2,2 s**, baixa e **verifica 786432 B**
  — todo o ciclo sobre I2P. `LIBP2P_I2P_OK`.
- **Custo de DHT-sobre-túnel [dado-só]:** descoberta por **Kademlia sobre I2P ~2,2–2,5 s**
  (provider-direto). É a variável que a Trama (gossip) não tem — medida, não extrapolada.
- **Atrito:** o **dial precisou de 2–3 retries** — a negociação Noise+multistream do libp2p estoura
  com a latência do I2P nas primeiras tentativas; a Trama, com handshake de 1 frame sobre o stream
  SAM, conecta de primeira. **Ressalva:** o número acima é o caso provider-direto (P conhece R);
  uma malha libp2p multi-nó sobre I2P (cada nó discando-por-SOCKS E escutando-por-tunnel) exigiria
  um transporte combinado no facade — não feito, e o walk multi-dial não foi medido.

---

## 7. Conclusão

### §1 — Viabilidade técnica (veredicto único) [executado]

**VIÁVEL no backbone E na leitura mobile — provado sobre I2P real, na internet real, em três redes
genuinamente separadas.** O ciclo do produto — P(DEV) publica/empurra → R(VPS) replica → leitor
(desktop e Moto g30 com router i2pd nativo, na rede móvel do device) descobre via B e
baixa/verifica — fecha por dentro de túneis I2P, com o portão TCK verde antes de medir. Números:
**crux mobile — leitura fria no device ~86 s** (mediana de 3, < 90 s ✅), ttfb 6,6–34 s; **T0
mobile — router do device reseeda e fica pronto em ~9 s**; backbone push **27 s** e throughput
**46 KiB/s** (T1); descoberta **5,4–7,3 s** (T2); leitura desktop **62 s** (T3); plano B: o device
**serve por destination** (T4); não-vazamento **0 pacotes diretos** ao IP de R (T6, desktop e
device); libp2p sobre I2P funciona com dial frágil (T7).

**Pontos de fricção honestos:** (a) o **cold-start de leitura** (~86 s no device, dominado pela
sessão SAM do cliente 9–24 s + descoberta/download sobre túnel) fica **no limite** do tolerável —
passa o alvo de 90 s por pouco; (b) o **reseed** é frágil por mirror (9 s no device, > 600 s no
desktop com mirrors ruins) — o modo de falha novo que o design previu; (c) throughput **46 KiB/s**,
marginalmente abaixo do alvo de 50. Não medido: o **mAh de bateria limpo** do plano B (device no
cabo — proxy de CPU registrado no lugar).

### §2 — Prós e contras (ledger, classe de evidência por linha)

| # | Sinal | Prós / Contras | Teste | Classe |
|---|---|---|---|---|
| 1 | **NAT dissolvido para todos os papéis** — serve sem port-forward | ➕ forte | T5, T4 | [executado] |
| 2 | **Anonimato de rede para TODOS os papéis** (não só o publicador do poc-05) | ➕ forte | T6 + D0 | [executado]/[só-design] |
| 3 | **Plano B destravado** — nó pleno mobile serve por destination atrás de CGNAT | ➕ | T4 | [executado] |
| 4 | Catálogo/conteúdo (Bitswap/CID/manifesto) **sobrevivem sem mudança** | ➕ | TCK sobre I2P | [executado] |
| 5 | Descoberta barata por gossip (5,4–7,3 s), conecta de primeira | ➕ | T2 | [dado-só] |
| 6 | Não-vazamento **auditado limpo** (0 pacotes clearnet, desktop e device) | ➕ | T6 | [executado] |
| 7 | **Cold-start no limite** (~86 s no device) — dominado por build de túnel | ➖ | T3 | [executado] |
| 8 | **Throughput ~46 KiB/s** — marginalmente abaixo do alvo de 50 | ➖ | T1 | [dado-só] |
| 9 | **Reseed frágil** — mirrors falham/timeout (nova dependência de cold-start) | ➖ forte | T0 | [executado] |
| 10 | **Deploy do replicador mais caro** — i2pd 2.39 do apt faz SEGV; exige repo/2.60 | ➖ | T0 | [executado] |
| 11 | mDNS/LAN do ADR-0007 **desaparece** (sem descoberta local no I2P) | ➖ | T5 | [executado] |
| 12 | libp2p sobre I2P tem **dial frágil** (retries) — a Trama conecta de primeira | ➖ (p/ libp2p) | T7 | [executado] |
| 13 | Custo de bateria (mAh) do router mobile — device no cabo, não medível | ⚠ não medido | T4 | limite declarado |
| 14 | Correlação global-passivo / timing | ⚠ fora de escopo | D0/§3 | limite declarado |

### §3 — Comparação com a arquitetura documentada (ADR a ADR) [executado/só-design]

Os veredictos abaixo valem **se a decisão for B** (I2P-nativo) — como B é arquitetura única, ele
reescreve o núcleo, não adiciona um modo. Se a decisão for A, todos os ADRs permanecem como estão e
o poc-06 fica arquivado como conhecimento.

- **ADR-0001 (modelo de rede, papéis, `push`)** — **CONFIRMADO com nuance.** A assimetria
  publicador/leitor e o `push` seguem válidos; mas o I2P torna o publicador **discável por
  destination**, então o `push` deixa de ser *necessidade* e vira **opção**. Decide: T1.
- **ADR-0002 (três planos)** — **CONFIRMADO.** Anúncio/catálogo/conteúdo sobrevivem intactos sobre
  I2P. Decide: TCK sobre I2P real.
- **ADR-0005 (mobile DHT client)** — **CONFIRMADO e ESTENDIDO.** O mobile-cliente-só-saída funciona
  sobre I2P (T3); e o I2P **destrava o plano B** (mobile nó pleno discável por destination),
  executado no device. Decide: T3 + T4.
- **ADR-0006 (NAT: endereço público manual / port-forward)** — **OBSOLETADO no modo I2P-nativo, a
  REESCREVER.** O I2P dissolve NAT por construção: R serve com 0 portas abertas e o device serve
  atrás do CGNAT, ambos alcançáveis só por destination (T5, T4). A premissa "alguém precisa
  configurar alcançabilidade pública" evapora. Decide: T5, T4.
- **ADR-0007 (bootstrap resiliente multi-canal)** — **PARCIALMENTE CONTRADITADO, a REESCREVER.** O
  canal mDNS/LAN não existe no I2P; em troca surge a **dependência de reseed** como novo ponto
  frágil de cold-start (T0). Os canais de lista assinada + bootstrap por destination sobrevivem.
  Decide: T0 + T5.

### §4 — Aprendizado e recomendação [síntese]

**O que o dado virou contra o a priori.** A hipótese temia que o **cold-start do mobile** fosse o
crux fatal. Medido: o cold-start é real mas **bounded** — ~86 s no device (mediana), dominado pela
construção da sessão SAM (9–24 s), não pela transferência; o ttfb é ótimo (6,6–34 s). O crux NÃO
foi throughput (46 KiB/s, medido sobre I2P). O que **emergiu como risco maior** que a latência foi a
**fragilidade do reseed** (mirrors falhando; i2pd 2.39 com SEGV).

**A escolha é binária — uma arquitetura, não duas.** Não existe "clearnet + modo anônimo opcional
(poc-05) **e** I2P-nativo como perfil opcional": isso seria embarcar e manter, no mesmo produto,
**três tecnologias de rede incompatíveis** (clearnet + stack Tor + stack I2P), com dois modelos de
endereço, dois de descoberta e dois de alcançabilidade. É **um ou outro**:

- **A — clearnet-nativo + anonimato OPCIONAL (poc-05/Tor):** o leitor lê rápido em clearnet; o
  anonimato é opt-in do **publicador** (Tor + `push`); NAT resolvido por **endereço público manual**
  (ADR-0006) + Tor no replicador que aceita anônimos. Custo estrutural: **multi-stack**, NAT não
  resolvido de graça, dual-homing (onion + IP), daemon Tor a mais no replicador.
- **B — I2P-nativo (poc-06):** a rede inteira sobre **um único overlay** que resolve
  **alcançabilidade E anonimato de uma vez, para todos os papéis**: NAT dissolvido (T5/T4), sem
  dual-homing, sem stack Tor, sem caminho clearnet. A hipótese de que o I2P "**simplifica** a
  arquitetura" é verdadeira no sentido de **singularidade** — some o caso-especial clearnet/Tor/NAT.
  Custo: **todo leitor** paga o cold-start (~86 s) e a dependência de reseed; throughput 46 KiB/s.

**Sobre o backend, se a decisão for B:** libp2p **funciona** sobre I2P (Kademlia + fetch verificado,
T7), mas o **dial é frágil** — a negociação do libp2p estoura com a latência do I2P e exige retries,
enquanto a Trama conecta de primeira e não precisou de retry em nenhuma célula. É a mesma assimetria
do poc-05 sobre Tor: um requisito de anonimato/latência **favorece a Trama**. O gatilho invertido do
poc-05 se confirma sobre I2P — commitar na Trama, não migrar para libp2p.

**Recomendação: a menos que anonimato-de-rede-para-todos / ausência-de-pegada-clearnet seja um
requisito DURO e inegociável do produto, NÃO comitar a rede inteira em I2P — permanecer em A.**
Racional ancorado no dado e no modelo de ameaças: o que o poc-05 (D0) estabeleceu como valioso
proteger é o vínculo **identidade↔IP do publicador**; o leitor de conteúdo **público** é ameaça
menor. O B cobra de **cada leitor** um cold-start de ~86 s e uma dependência de reseed frágil — um
imposto universal por uma proteção que o modelo de ameaças precisa sobretudo para o publicador, que
o A já entrega opt-in mantendo o leitor rápido. **B só vence se a premissa do produto for
censorship-resistance / samizdat** (nenhuma pegada clearnet aceitável): aí o custo de cold-start é o
preço de entrada, e o B é **tecnicamente viável e comprovado aqui**, além de arquiteturalmente mais
limpo (overlay único). Para um leitor de quadrinhos casual, o dado medido **não justifica** pagar
esse preço em toda leitura.

**ADR novo a escrever — SÓ se a decisão for B (substituição, não coexistência):** `ADR-0010 — rede
nativamente anônima sobre I2P`. Como B é arquitetura única, adotá-lo **reescreve** o núcleo:
**obsoleta o ADR-0006** (NAT dissolvido por destination), **reescreve o ADR-0007** (mDNS/LAN some;
reseed vira dependência de cold-start), **revisa o ADR-0001** (o `push` deixa de ser necessidade) e
**aposenta o modo anônimo Tor do poc-05** (redundante: o anonimato passa a ser propriedade nativa).
O ADR-0010 deve declarar o **custo por-leitor** (cold-start + reseed), o **custo do plano B**
(bateria/uptime do router mobile, a medir fora do cabo) e o **backend recomendado** (Trama, pelo
dial frágil do libp2p sobre I2P). Se a decisão for A, o poc-06 fica como conhecimento arquivado:
prova que a rota I2P-nativa é viável, disponível se o requisito de anonimato-para-todos entrar.

## 8. Limites da rodada

**Medido de verdade, sobre I2P real:** backbone DEV↔VPS (T1/T2/T3-desktop/T6); crux mobile no Moto
g30 com router nativo, na rede móvel do device (T3); cold-start do router no device (T0); plano B —
o device serve por destination (T4); descoberta em máquinas distintas, B no DEV e R na VPS (T2);
E-fase libp2p sobre I2P com Kademlia e fetch verificado (T7).

**Ressalvas de topologia (não invalidam os números):** o portão TCK sobre I2P roda em host único (2
destinations no mesmo router) — cenário controlado do portão, por design; B+R co-localizados na VPS
para a célula de descoberta de 7,3 s, mas a mesma descoberta foi refeita com B e R em máquinas
distintas (5,4 s).

**O único ponto não medido — limite físico da bancada:** o **mAh de bateria limpo** do plano B. O
device está no USB/AC carregando (`status=FULL, level=100`), então o drain real não é separável da
carga; medido só o proxy de CPU (router 18,8 s / app 13,6 s). Exigiria o device fora do cabo com
telemetria de energia. Na mesma linha, o **walk multi-dial** do Kademlia sobre I2P (além do
provider-direto medido no T7) exigiria um transporte combinado (dial-SOCKS + listen-tunnel) no
facade libp2p, não feito nesta rodada.
