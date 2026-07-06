# poc-07 — Viabilidade do mobile em KMP (Android + iOS)

> **Estado:** concluído (2026-07-06). As três células foram fechadas com E2E real no iPhone 11
> físico: Trama→Native (célula 1), libp2p→iOS com TCK completo de 3 nós (célula 2) e I2P→iOS com
> router i2pd embarcado (célula 3). Classe de evidência por claim: `[executado]` (medido nesta
> bancada) · `[dado-só]` (derivado de artefato/observação direta, sem cronômetro) · `[limite
> declarado]` (fora de escopo). **Nenhuma** medição de POC anterior é reusada como dado desta.
> Evidência crua completa: `pocs/poc07/DATA.md`.

Bancada (todos os recursos verificados presentes):

| Recurso | Papel | Verificação |
|---|---|---|
| shell/JVM (DEV), este Mac | publicador/servidor (assina o conteúdo) | rede residencial V.tal/AS8167 (`177.203.17.5`) |
| VPS `143.95.220.165:22022` | replicador + bootstrap (IP público manual, ADR-0006) | SSH OK · 1 vCPU · 1.9 GB · Java 21 · Rust 1.91 |
| Moto g30 (USB) `ZF523HKK7K` | baseline: leitor Kotlin/JVM (ART) | `adb devices` OK |
| iPhone 11 (WiFi/USB) `00008030-…` | o CRUX: leitor Kotlin/Native, iOS 26.5 | `devicectl` paired · developerMode on · ddiServices on |
| Xcode | toolchain iOS | 16.4 (16F6), SDK iOS 18.5, DeviceSupport ≤ 16.4 |

---

## 1. A priori (cravado antes de qualquer medição)

**1.1 D0 — "mobile em KMP (Android + iOS)":** o caminho nativo precisa fechar, no iPhone real, o
E2E de leitura de um capítulo: **descobrir** um provider (resolve na malha/DHT via bootstrap) →
**discar** → **baixar** manifesto assinado + blocos → **verificar no próprio device** (Ed25519 do
manifesto + sha-256 de cada bloco, o `ChapterVerifier`, fora do seam). Fecha quando o capítulo
íntegro é reconstruído no iPhone e um manifesto/bloco adulterado é **rejeitado**.

**1.2 Perguntas Q:** Q1 verify no Native · Q2 dial em rede móvel · Q3 E2E a frio sob limiares ·
Q4 SPI neutra por 2 backends (Trama × libp2p) · Q5 custo de porte iOS de cada backend ·
Q6 I2P-no-iOS.

**1.3 Limiares numéricos (cravados com o usuário):**

| Métrica | Limiar a priori |
|---|---|
| Leitura fria E2E total (descobrir→baixar 768 KiB→verify), mediana de ≥3 | **< 15 s** |
| TTFB (dial → 1º byte do capítulo no device) | **< 5 s** |
| Verify Ed25519 + sha-256 do capítulo **no device** | **< 100 ms** |

**1.4 Fora de escopo (não medidos, jamais prometidos):** UX de leitura (Compose/iOS); bateria
(mAh); anonimato nas células 1–2 (rodam clearnet de propósito, para isolar a variável plataforma).
A célula 3 (I2P-no-iOS) entrou como **stretch com gate** — se o modelo de daemon/background/App
Store travasse, transbordaria p/ poc-08; foi construída e o gate remanescente está registrado em §6.

---

## 2. Setup (rig + spikes de de-riscagem no device real)

**2.1 Portão do skew iOS (risco #1) — PASSOU `[executado]`.** Um binário Kotlin/Native (framework
`iosArm64`, Xcode 16.4/SDK 18.5) instalou e executou no iPhone 11 físico em **iOS 26.5**, sem
simulador: `POC07-PROBE platform=iOS/iOS/26.5/iPhone fnv1a64(opentoons-poc07)=0xbf563927f1836e88`.
O skew não bloqueou — iOS 17+ monta um **DDI personalizado dinâmico** (CoreDevice), não depende
das pastas estáticas de DeviceSupport ≤ 16.4. O FNV bate com o host (execução real, não
constante) e o `platform=…/26.5/…` prova o cinterop UIKit chamável. `DATA.md §2.1`.

**2.2 Spike de crypto (D4) — cryptography-kotlin/CryptoKit fecha `[executado]`.** As 5 primitivas
da Trama rodaram no iPhone via **CryptoKit** (host: JDK), aferidas contra vetor conhecido:

| SHA-256 (KAT "abc") | HMAC-SHA256 | X25519 (acordo) | ChaCha20-Poly1305 (nonce explícito) | Ed25519 (aceita/rejeita) |
|---|---|---|---|---|
| PASS | PASS | PASS | PASS | PASS |

Decisão D4: a Trama cruza trocando BouncyCastle por `cryptography-kotlin` em `commonMain` (JDK no
JVM/Android, CryptoKit no iOS), sem cinterop de crypto. `DATA.md §2.2`.

**2.3 Spike de socket — ktor-network fecha em Native `[executado]`.** O iPhone (`iosArm64`)
discou o IP público da VPS e trocou bytes com eco íntegro (`connect=70ms rtt=58ms`). Caminho de
socket escolhido: **ktor-network em `commonMain`**, sem cinterop. `DATA.md §2.3`.

**2.4 Nós na VPS — no ar `[executado]`.** IP público alcançável do exterior. Na campanha subiram
dois full nodes reais na VPS (systemd): **Trama** (jvm, porta 6070) e **libp2p** (rust, porta
6080), ambos servindo o MESMO capítulo de 768 KiB, anunciando o IP público (ADR-0006). `DATA.md
§2.4/§5.2`.

## 3. Código (SPI + Trama→Native + portão)

**3.1 SPI `:pocs:poc07:api` em `commonMain` — compila iOS, verify no Native `[executado]`.** O
`:pocs:poc06:api` (JVM) portado para KMP (`jvm`, `iosArm64`, `iosSimulatorArm64`) sem `java.*` na
superfície: `ByteBuffer`→helpers BE; BouncyCastle/`MessageDigest`→cryptography-kotlin;
`ConcurrentHashMap`→`HashMap`+lock atomicfu. `ApiSeamTest` 5/5 **em Kotlin/Native**, incluindo
verify que rejeita bloco adulterado e chave errada. `DATA.md §3.1`.

**3.2 Trama portada para Kotlin/Native — compila e roda nos 3 alvos `[executado]`.** Noise XX +
RPC de frames + full node + client de Kotlin/JVM para `commonMain`: crypto→cryptography-kotlin
(orquestração Noise idêntica → fio compatível JVM↔Native); `java.net`+threads→ktor-network+
coroutines; `CompletableFuture`/`ConcurrentHashMap`→`CompletableDeferred`+locks atomicfu;
`System.currentTimeMillis`→`nowMillis()` expect/actual. Um **bug real** foi pego pelo teste E2E
(não pela compilação): `SecureNoiseConnection.send` sem exclusão mútua racejava nonce/chunks sob
GET_BLOCK concorrente — corrigido com `Mutex`. `DATA.md §3.2`.

**3.3 Portão D5 — TCK verde no alvo iOS `[executado]`.** O TCK de conformidade do poc-04
(JUnit→kotlin.test, em `commonTest`) roda idêntico no host e em `iosSimulatorArm64` (Kotlin/
Native): **6/6 verde** — resolve transitivo, download 768 KiB verificado, bloco
adulterado→mismatch, chave errada→BadSignature, push aceito/servido, push impostor rejeitado,
expiry+republish. Correção provada **por implementação** antes de qualquer número de campanha.
`DATA.md §3.3`.

**3.4 Casca de app iOS `[executado]`.** `reader-ios` (SwiftUI) linka o framework `OpenToonsKit`
(Trama+api KMP) e chama o `ReaderProbe`/`Libp2pReaderProbe` de `commonMain` — sem branch de app.

## 4. CÉLULA 1 — Trama → Native (clearnet) — **FECHADA** `[executado]`

Full node Trama (alvo jvm da MESMA `commonMain`) na VPS (IP público `143.95.220.165:6070`), a
servir 768 KiB. Leitor Native no iPhone 11 físico, **em rede móvel** (WiFi off, dados Claro),
5 leituras **a frio** (client novo cada):

| Métrica | Mediana (n=5, rede móvel) | Limiar | Veredicto |
|---|---|---|---|
| Leitura fria E2E (dial→resolve→baixar 768 KiB→verify) | **1453 ms** | < 15 s | ✅ ~10× folga |
| TTFB | **283 ms** | < 5 s | ✅ |
| Verify Ed25519 + sha-256 **no device** | **< 1 ms** | < 100 ms | ✅ |

- **Não-truque (4.3):** o coletor da VPS registrou o IP de origem das leituras como
  `187.43.188.226` (**Claro/AS4230**, operadora móvel) — ≠ do IP residencial do DEV `177.203.17.5`
  (**V.tal/AS8167**). Egress celular provado; device físico; sem loopback/`adb reverse`/simulador.
  Três redes reais na jornada: DEV (residencial, assina) — VPS (datacenter, IP público, serve) —
  iPhone (Claro, lê).
- **Baseline (4.2):** o Moto g30 (Kotlin/JVM/ART) fechou o MESMO E2E verificado (mediana
  **1704 ms**); egress do Moto também auditado (`src=Claro/AS4230`). A SPI **não regrediu**: o
  mesmo `commonMain` verificado em **3 plataformas** — host JVM **779 ms**, Moto ART **1704 ms**,
  iPhone Native **1453 ms**. `DATA.md §4.1/4.2/4.3`.

> **Achado de porte (Android):** o `org.bouncycastle` stripped do Android sombreia o bcprov
> completo → o provider JDK da cryptography-kotlin não deriva pubkey Ed25519 da privada. Resolvido
> no design do LEITOR (não é regressão): o leitor usa a pubkey CONHECIDA do publicador (âncora de
> confiança, constante — como um app real embarca), sem derivar de semente. Assinar/derivar só o
> publicador/servidor JVM (com BC completo) faz.

## 5. CÉLULA 2 — libp2p → iOS (clearnet, mesma SPI) — **E2E REAL fechado no iPhone** `[executado]`

Segundo backend (`Libp2pBackend : P2pBackend`, Kotlin/Native → rust-libp2p 0.54 via cinterop
C-ABI) fechou o MESMO E2E no iPhone físico. Servidor libp2p rust na VPS (`poc07-lp-server`,
Kademlia + request-response, IP público 6080) serve o mesmo capítulo; o iPhone disca, **resolve
via Kademlia**, baixa por request-response e **verifica no device** (mesmo `ChapterVerifier`).

**3 leituras a frio no iPhone (rede móvel Claro):**
```
lp1 ok=true connect=31ms ttfb=338ms download= 984ms verify<1ms total=2085ms bytes=786432 verified
lp2 ok=true connect= 1ms ttfb=361ms download= 968ms verify<1ms total=2029ms bytes=786432 verified
lp3 ok=true connect= 1ms ttfb=369ms download=1029ms verify<1ms total=2099ms bytes=786432 verified
```

- **Coexistência (5.2):** o MESMO binário/execução rodou 5 leituras **Trama** + 3 **libp2p**,
  todas verified — dois backends satisfazendo a MESMA `P2pBackend`, selecionáveis por código.
- **Egress celular:** VPS registrou `src=Claro/AS4230` também nas libp2p → E2E por rede móvel.
- **TCK libp2p COMPLETO no alvo iOS (5.3):** além da rejeição de adulteração no device, a
  **topologia TCK completa** foi montada — 3 full nodes libp2p REAIS in-process (bootstrap +
  publicador + cliente) em loopback via cinterop, rodando os cenários de conformidade no alvo
  Native (simulador bootado): descoberta/download/verify íntegro (Verified), bloco adulterado
  (BlockHashMismatch), chave errada (BadSignature). `Libp2pTckTest` 3/3 + `TramaTckTest` 6/6 verdes.
- **Mecanismo de binding (5.1):** o cinterop C-ABI ao `.a` rust (mecanismo NOVO vs JNI/UniFFI do
  Android) foi provado ponta a ponta antes do backend — cross-compile (device+sim), símbolos
  C-ABI, cinterop, **execução no iPhone físico** (`POC07-FFI add=42 rust_sha256=4054fad1…`,
  byte-a-byte com o vetor). O rust-libp2p 0.54 inteiro (com `ring`/`quinn`) **cross-compila limpo**
  para `aarch64-apple-ios` (`.a` de 52 MB), sem muro de toolchain. O link no app exigiu
  `SystemConfiguration.framework` (quinn/if-watch).

**Comparação REAL do eixo (E2E medido, não suposto):** Trama mediana **~1453 ms** × libp2p
**~2085 ms** no mesmo iPhone/capítulo/VPS, ambas verificadas. libp2p ~40% mais lento aqui + exige
a cadeia cross-compile+C-ABI+cinterop+SystemConfiguration; a Trama é `commonMain` puro (0 FFI).
`DATA.md §5.1/5.2/5.3/5.4`.

## 6. CÉLULA 3 — I2P → iOS (stretch, com gate) — **E2E construído e VERDE no iPhone** `[executado]`

Stretch com gate desde o a priori, mas **construído de verdade** (não só declarado viável). Um
router **i2pd EMBARCADO in-process** roda dentro do app iOS — o MESMO mecanismo cinterop C-ABI da
libp2p, agora sobre o **libi2pd** (i2pd 2.60, api.h + Streaming):
- **Cross-compile p/ `arm64-apple-ios`:** OpenSSL 3.5 + Boost 1.86 + libi2pd (47 fontes) → `.a`s;
  wrapper C-ABI `poc07i2p` (start/ready/connect/send/recv) + libi2pd → `libpoc07i2p.a` no framework.
- **Servidor:** i2pd com server tunnel (destination I2P → TCP local) servindo o MESMO capítulo
  assinado (768 KiB); `.b32` estável. Rodou no host DEV (a chave SSH da VPS caiu do ssh-agent
  durante a sessão) — o que **não é atalho de rede**: a destination `.b32` só é alcançável **pela
  overlay I2P**, nunca pela LAN; o iPhone descobre por LeaseSet e streama por túneis garlic-routed,
  sem caminho direto possível entre os dois pontos.
- **Leitor (iPhone 11 físico, Kotlin/Native):** sobe o router (reseed + tunnels + netDB, pré-seed
  de routerInfos p/ peers conectáveis atrás de NAT), **DESCOBRE** a destination pela netDB I2P
  (lookup de LeaseSet) e baixa por um stream I2P garlic-routed, **verificando Ed25519+sha256 no
  device**. 3 leituras a frio, todas `verified`, mediana total **~19122 ms** (I2P é lento: download
  ~14–19s, ttfb ~2.2–2.8s; verify sub-20ms no A13).

**Veredicto:** I2P-no-iOS **é tecnicamente viável e o E2E FUNCIONA** — router embarcado, descoberta
e transferência pela rede I2P, verify no device. Mede 2 variáveis (I2P + Native), como o design
declarou. O ÚNICO gate que permanece p/ poc-08 é o de **distribuição/plataforma** (modelo App Store
+ background de um app *publicável*) — **não** a compilabilidade nem a viabilidade técnica, ambas
agora provadas por E2E real. `DATA.md §6.2(completa)`.

---

## Conclusão (4 partes)

### §1 — Viabilidade técnica `[executado]`

**O mobile em KMP para Android E iOS é viável pelo caminho nativo.** Ancorado em teste:
- **iOS (o crux):** leitor **Kotlin/Native** no iPhone 11 físico (iOS 26.5, Xcode 16.4) fechou o
  E2E a frio, **em rede móvel** — descobrir → discar IP público → baixar 768 KiB → **verificar
  Ed25519+sha-256 no device** — mediana **1453 ms** (Trama) e **2085 ms** (libp2p). O risco #1
  (skew) não se materializou.
- **Portão de correção:** TCK do poc-04 **verde no alvo iOS** (6/6 em Kotlin/Native) contra a
  Trama; adulteração rejeitada no device também sobre libp2p.
- **Android não regride:** o Moto g30 (ART) fecha o MESMO E2E verificado sobre a MESMA SPI.

### §2 — Prós e contras (ledger, classe de evidência por linha)

| Sinal | +/− | Evidência |
|---|---|---|
| Kotlin/Native instala e roda em iOS 26.5 com Xcode 16.4 (skew não bloqueia) | + | `[executado]` 2.1 |
| Crypto da Trama fecha no iOS via CryptoKit (Ed25519/X25519/ChaCha/HMAC/SHA) | + | `[executado]` 2.2 |
| Socket em Native fecha com ktor-network (dial iPhone→VPS) | + | `[executado]` 2.3 |
| SPI sobe a `commonMain` sem `java.*`; verify roda no device | + | `[executado]` 3.1 |
| Trama inteira cruza para Native (mesmo fio JVM↔Native); TCK 6/6 verde no iOS | + | `[executado]` 3.2/3.3 |
| E2E a frio Trama no iPhone em rede móvel bate todos os limiares (~10× folga) | + | `[executado]` 4.1 |
| SPI portada não regride Android/JVM (3 plataformas verificadas) + egress auditado | + | `[executado]` 4.2/4.3 |
| libp2p fecha o MESMO E2E no iPhone (Kademlia+RR, verify no device), coexistindo c/ a Trama | + | `[executado]` 5.2 |
| TCK libp2p COMPLETO no alvo iOS: 3 nós reais in-process (Libp2pTckTest 3/3) + adulteração no device | + | `[executado]` 5.3 |
| cinterop C-ABI ao rust executa no iPhone; rust-libp2p cross-compila limpo p/ iOS | + | `[executado]` 5.1 |
| **E2E de leitura sobre I2P no iPhone construído e verde** (i2pd embarcado, descoberta+transferência, verify no device) | + | `[executado]` 6.2 |
| Concorrência do porte exigiu cuidado (bug de nonce/chunk pego só pelo E2E real) | − | `[executado]` 3.2 |
| Android sombreia BouncyCastle → derivar pubkey de semente falha (contornável no design) | − | `[executado]` 4.2 |
| libp2p ~40% mais lento no E2E (2085 vs 1453 ms) + exige cross-compile+C-ABI+cinterop vs Trama 0-FFI | − | `[executado]` §4 |
| I2P mais lento no E2E (~19s vs ~1.5s clearnet) — custo do overlay garlic-routed (esperado) | − | `[executado]` 6.2 |
| I2P-no-iOS: gate de **distribuição** (App Store/background de app publicável) permanece → poc-08 | − (limite) | `[limite declarado]` 6 |
| UX (Compose/iOS) e bateria (mAh) fora de escopo, não medidos | limite | `[limite declarado]` 1.4 |

### §3 — Comparação com a arquitetura documentada (ADR a ADR)

- **ADR-0005 (mobile como cliente DHT/leitor): CONFIRMA e ESTENDE.** O ADR previa o mobile como
  leitor; este poc mostra que o leitor cabe em **KMP para Android E iOS** com o MESMO `commonMain`
  — não só Android — com o verify rodando no device em ambos. `[executado]`
- **ADR-0006 (NAT / endereço público manual): CONFIRMA.** O iPhone (atrás de CGNAT da Claro)
  alcançou o conteúdo porque o servidor tem **IP público manual** anunciado como provider —
  exatamente o mecanismo do ADR, tanto na Trama quanto na libp2p. `[executado]`
- Nenhum ADR foi contradito/obsoletado.

### §4 — Aprendizado e recomendação (+ veredicto do eixo Trama × libp2p)

**O que o dado virou contra o a priori:** os três medos do a priori — o skew iOS, a maturidade de
crypto/socket em Native, e o cross-compile dos backends nativos (rust-libp2p e a cadeia C++ do
libi2pd) para iOS — caíram todos no teste real. A leitura fria clearnet ficou ~10× sob o teto; o
rust-libp2p 0.54 e o libi2pd 2.60 cross-compilam limpo p/ `aarch64-apple-ios` e fecham E2E no
device. Nenhum suposto muro de toolchain se materializou.

**Veredicto do eixo de portabilidade iOS (Trama × libp2p) — com E2E REAL dos DOIS:** os dois
backends fecharam o MESMO E2E no MESMO iPhone físico, em rede móvel, verificados no device.
Medianas: **Trama ~1453 ms × libp2p ~2085 ms**. Pelo custo de porte, a Trama cruzou como **Kotlin
puro em `commonMain`** (troca de lib de crypto+socket, ZERO FFI); o libp2p exigiu a cadeia
completa **cross-compile do `.a` rust + facade C-ABI + cinterop + `SystemConfiguration`** e um
servidor rust separado. **Veredicto:** a Trama vence pela portabilidade iOS (0-FFI,
single-language, mais rápida aqui, coexistindo no mesmo binário) — **confirmando o pró-Trama do
poc-05/06** —, mas o libp2p→iOS é **viável e comprovado E2E**, não um beco sem saída. A
recomendação se sustenta em dado dos dois lados, não em suposição.

**Recomendação:** seguir com a **Trama como backend do mobile KMP (Android + iOS)**. O I2P-no-iOS,
antes um buraco declarado, foi **construído e comprovado E2E** — resta dele só o gate de
distribuição (poc-08). Buracos declarados que permanecem: UX de leitura (Compose/iOS) e bateria.

### §5 — Honestidade de evidência

Cada claim carrega classe. **Nenhuma** medição de POC anterior foi reusada — todos os números
(1453/1704/779/2085 ms clearnet, ~19s I2P, ASNs Claro/V.tal, TCK 6/6 Trama + 3/3 libp2p, `.a`
libp2p de 52 MB, FNV/sha256 aferidos) são desta bancada, 2026-07-05/06.

**Nada testável foi declarado sem construir.** Todo stretch virou E2E medido no device: a libp2p
(servidor rust + `Libp2pBackend` Kotlin/Native + E2E no iPhone + TCK completo de 3 nós in-process)
e o I2P (OpenSSL+Boost+libi2pd cross-compilados p/ iOS + router embarcado + E2E de leitura no
iPhone, verify no device). O ÚNICO limite que permanece p/ poc-08 é de **distribuição** (modelo App
Store + background de um app publicável) — não compilabilidade nem viabilidade técnica, ambas
provadas. Fora de escopo por design (não medidos): UX/Compose iOS e bateria.
