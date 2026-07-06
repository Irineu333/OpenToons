# poc-07 — log de dados cru (evidência por execução)

> Cada bloco é uma execução real datada, com o comando e a saída literal. O relatório
> (`docs/pocs/poc07-report.md`) cita estes blocos. Nada aqui é suposto ou inventado.

## Bancada (2026-07-05)

- **VPS**: `ssh -p 22022 root@143.95.220.165` → `OK-VPS`, Ubuntu 6.8, `nproc=1`, 1967 MB RAM,
  IP público `143.95.220.165` (confirmado por `curl ifconfig.me` de dentro da VPS).
- **Android**: `adb devices` → `ZF523HKK7K device` (Moto g30, caprip).
- **iPhone**: `devicectl list devices` → `iPhone de Irineu` `00008030-000911112120802E`,
  iPhone11,1 (iPhone12,1), `available (paired)`, `developerModeStatus: enabled`,
  `ddiServicesAvailable: true`, `osVersionNumber: 26.5`.
- **Xcode**: 16.4 (16F6); SDK `iPhoneOS18.5`; DeviceSupport instalado ≤ 16.4; identidade de
  assinatura `Apple Development: Irineu A Silva`, team `7BMPL2Z939` (AIQFOME LTDA).

---

## 2.1 — Portão do skew iOS (risco #1)  [executado · 2026-07-05 05:20]

**Hipótese testada:** um binário Kotlin/Native compilado com Xcode 16.4 (SDK iOS 18.5,
DeviceSupport ≤ 16.4) instala e EXECUTA no iPhone 11 físico em iOS **26.5** — sem simulador.

**Método (sem truque):**
1. Módulo KMP `:pocs:poc07:probe` com alvo `iosArm64` → framework estático `Probe.framework`
   (`./gradlew :pocs:poc07:probe:linkDebugFrameworkIosArm64` → BUILD SUCCESSFUL, 26 MB).
2. Aferição da régua (D6): `fnv1a64("opentoons-poc07")` computado independente em Python =
   `0xbf563927f1836e88`; `jvmTest` verde contra esse valor.
3. App SwiftUI mínimo (`probe-ios`, xcodegen) importa `Probe`, chama `Probe.shared.hello()`.
4. `xcodebuild -allowProvisioningUpdates -destination id=<udid>` → **BUILD SUCCEEDED**;
   profile auto-gerado `iOS Team Provisioning Profile: *`, assinado com a identidade real.
5. `devicectl device install app` → **App installed** (bundle `org.opentoons.poc07.probe`,
   instalado em `/private/var/containers/Bundle/Application/…`).
6. `devicectl device process launch --console` no device físico.

**Saída literal do device:**
```
Launched application with org.opentoons.poc07.probe bundle identifier.
POC07-PROBE platform=iOS/iOS/26.5/iPhone fnv1a64(opentoons-poc07)=0xbf563927f1836e88
```

**Veredicto:** o skew **NÃO** bloqueia. Kotlin/Native instala e roda em iOS 26.5 com Xcode
16.4 (o DDI personalizado é montado dinamicamente por CoreDevice — `ddiServicesAvailable:
true` — não depende das pastas estáticas de DeviceSupport ≤ 16.4). O valor FNV bate byte a
byte com o host → execução Kotlin genuína (aritmética 64-bit real no ARM, não constante
linkada). `platform=…/26.5/…` prova o cinterop UIKit/Foundation chamável no device.
Classe de evidência: **[executado]**.

---

## 2.2 — Spike de crypto (D4)  [executado · 2026-07-05 05:30]

**Hipótese:** uma lib de crypto KMP fecha as 4+1 primitivas da Trama no `iosArm64` com o
controle que o Noise XX exige (nonce ChaCha explícito), sem hand-roll.

**Candidato medido:** `cryptography-kotlin 0.6.0` — provider **JDK** no host, **CryptoKit**
no device. Aferição (D6): SHA-256 contra KAT `"abc"`; X25519 pela propriedade de acordo;
ChaCha por ida-e-volta com nonce 12B explícito + AAD; Ed25519 aceitando a boa e rejeitando a
adulterada. Símbolo X25519 = `XDH.Curve.X25519`; AEAD com `encryptWithIvBlocking(iv,pt,aad)`.

**Host (jvmTest, provider JDK):** todas PASS.
```
POC07-CRYPTO provider=JDK
  sha256: PASS · hmac-sha256: PASS · x25519: PASS · chacha20poly1305: PASS · ed25519: PASS
```

**Device (iPhone 11, iOS 26.5, provider CryptoKit), saída literal:**
```
POC07-CRYPTO provider=CryptoKit
  sha256: PASS (KAT abc ok)
  hmac-sha256: PASS (tag 32B, verify ok)
  x25519: PASS (acordo ok, 32B)
  chacha20poly1305: PASS (roundtrip, tag16, nonce explícito)
  ed25519: PASS (aceita boa, rejeita adulterada, sig 64B)
```

**Veredicto D4:** cryptography-kotlin (CryptoKit/JDK) cobre Ed25519 + SHA-256 + HMAC-SHA256 +
X25519 + ChaCha20-Poly1305(nonce explícito) no alvo iOS. Caminho escolhido pelo dado: a Trama
cruza para Native trocando BouncyCastle por essa lib em `commonMain`, sem cinterop de crypto.
Nota operacional: o túnel `devicectl` sobre WiFi caiu 1× (CoreDeviceError 4000) e reconectou
na 1ª retentativa — transitório, não relacionado ao Kotlin/Native. Classe: **[executado]**.

---

## 2.3 — Spike de socket (dial TCP em Kotlin/Native)  [executado · 2026-07-05 05:38]

**Hipótese:** ktor-network fecha o dial TCP em Kotlin/Native (substituto do `java.net.Socket`,
inexistente no Native). Rig: echo TCP cru na VPS (`0.0.0.0:5599`, python3, `ufw inactive`);
frame `[len BE][payload]`; aferição (D6) = eco byte-a-byte igual ao enviado.

- **Alcançabilidade externa** (do Mac, rede residencial → IP público da VPS):
  `printf 'POC07-REACH-TEST' | nc 143.95.220.165 5599` → devolveu `POC07-REACH-TEST`.
- **Host (jvmTest):** `POC07-SOCK PASS host=143.95.220.165:5599 connect=35ms rtt=50ms bytes=40`.
- **Device (iPhone, ktor-network iosArm64), saída literal:**
  `POC07-SOCK PASS host=143.95.220.165:5599 connect=70ms rtt=58ms bytes=40`

**Veredicto:** ktor-network disca do iPhone ao IP público da VPS e troca bytes ponta a ponta,
com eco íntegro. Caminho de socket da Trama→Native escolhido: **ktor-network em `commonMain`**
(não precisou de NSStream/Network.framework via cinterop). Classe: **[executado]**.
Nota: o túnel `devicectl`/WiFi exigiu 4 retries no install e 1 no build (`CoreDeviceError
1000/4000`) — flakiness de WiFi, não do binário; sempre reconectou.

## 2.4 — Replicador/bootstrap na VPS  [parcial · 2026-07-05]

Alcançabilidade da VPS pelo IP público confirmada (echo 5599 acima). O replicador/bootstrap
**Trama** propriamente dito depende do porte da Trama→KMP (task 3.2) — será subido na célula 1.

---

## 3.1 — SPI (`:pocs:poc07:api`) em commonMain  [executado · 2026-07-05]

Porte do `:pocs:poc06:api` (JVM) para KMP `commonMain`, alvos `jvm` + `iosArm64` +
`iosSimulatorArm64`, SEM `java.*` na superfície:
- `ByteBuffer`/`DataInput/OutputStream` → helpers BE próprios (`Bytes.kt`) — blob de wire idêntico.
- `MessageDigest`/BouncyCastle Ed25519 → cryptography-kotlin (`Crypto.kt`): JDK+BC no host
  (o provider JDK deriva pubkey Ed25519 da privada só com BC no classpath), CryptoKit no iOS.
- `ConcurrentHashMap` → `HashMap` guardado por lock do atomicfu (`MemoryBlockstore`).
- `@JvmInline value class`, `AutoCloseable` — os de `kotlin.*` (comuns).

**Compilação:** `compileKotlinJvm` + `compileKotlinIosArm64` + `compileKotlinIosSimulatorArm64`
→ todos SUCCESSFUL. **Testes (`ApiSeamTest`)**, host e Native:
```
iosSimulatorArm64Test: testsuite ApiSeamTest tests=5 skipped=0 failures=0
  deterministicIdentity · signAndVerify · verifierAcceptsIntact
  verifierRejectsTamperedBlock · verifierRejectsWrongKey   (todos PASSED em Kotlin/Native)
```
**Veredicto:** a SPI compila para o alvo iOS e o **verify (Ed25519 + sha-256) roda em
Kotlin/Native**, aceitando o íntegro e rejeitando adulteração/chave errada — fora do seam
(D7). Classe: **[executado]**.

---

## 3.2 / 3.3 — Trama portada para Native + TCK verde no alvo iOS  [executado · 2026-07-05]

**3.2 porte:** `:pocs:poc07:trama` — motor Noise XX + RPC de frames + full node + client, de
Kotlin/JVM para `commonMain` (alvos jvm + iosArm64 + iosSimulatorArm64). Trocas:
- BouncyCastle (X25519/ChaCha/HMAC/SHA) → cryptography-kotlin (`NoiseCrypto`), orquestração
  Noise idêntica → fio compatível JVM↔Native.
- `java.net.Socket` + threads → **ktor-network** + coroutines (`Transport`, IO suspensa).
- `ExecutorService`/`CompletableFuture`/`ConcurrentHashMap` → escopo de coroutines +
  `CompletableDeferred` + mapas sob lock atomicfu (`Rpc`, `TramaFullNode`).
- `System.currentTimeMillis` → `nowMillis()` expect/actual.
- **Bug real encontrado e corrigido pelo teste:** `SecureNoiseConnection.send` sem exclusão
  mútua — 3 respostas GET_BLOCK concorrentes na mesma conexão intercalavam chunks e racejavam
  o nonce do CipherState → "conexão fechada". Corrigido com `Mutex` por sentido (o poc-06 usava
  `synchronized`). Sem o teste real, passaria batido.

**3.3 portão D5 — TCK de conformidade** (JUnit→kotlin.test, em `commonTest`, roda idêntico nos
dois alvos). Levanta full nodes + client Trama sobre TcpTransport (loopback) — download de 768
KiB, adulteração, chave errada, push, expiry/republish:
```
jvmTest                : TramaTckTest tests=6 failures=0 errors=0  (4.60s)
iosSimulatorArm64Test  : TramaTckTest tests=6 failures=0 errors=0  (4.27s)   ← Kotlin/Native
  resolveDescobreProviderEDownloadVerificaIntegro · blocoAdulteradoERejeitado
  manifestoDeChaveErradaERejeitado · pushAutenticadoGravadoEServido
  pushDeChaveErradaRejeitadoAntesDeGravar · expiryAposMorteERepublishAposReviver
```
**Veredicto:** a Trama (Noise XX real + RPC + membership + push) roda em Kotlin/Native e o TCK
está VERDE no alvo iOS — o portão de correção do poc-04 fechado por implementação, ANTES de
qualquer número de campanha. Simulador só para o portão (D-rules); campo é o device físico.
Classe: **[executado]**.

---

## 2.4 (completa) — Full node Trama na VPS  [executado · 2026-07-05]

`:pocs:poc07:node` (alvo jvm da MESMA Trama de commonMain) empacotado (`installDist`), scp para
a VPS (Java 21) e rodando como unidade systemd `poc07node`:
```
VPS-NODE-UP idHex=34f6df44dec8a6e34d11c6b87a1fd682539db5f013b1a5c2ea6a609884052374
            listen=0.0.0.0:6070 advertise=143.95.220.165:6070
VPS-NODE-OBRA obra=opentoons/serie-teste bytes=786432 contentKey=fa49782649545fb3...
```
Bound `*:6070`, **alcançável externamente** (`nc` do Mac → succeeded). ADR-0006: endereço
público manual anunciado. Nota operacional: `pkill -f <padrão>` na VPS casava a própria linha
de comando do meu shell remoto (continha o padrão) e o matava — resolvido com `systemd-run`.

## 3.4 / 4.1(WiFi) — Leitor Native no iPhone: E2E on-device  [executado · 2026-07-05]

App `reader-ios` (SwiftUI) linka o framework estático `OpenToonsKit` (Trama+api KMP) e chama o
MESMO `ReaderProbe` de commonMain. Aferição prévia no **host contra a VPS real**:
`POC07-READ ok=true connect=172ms ttfb=98ms download=324ms verify=3ms total=779ms verified`.

**No iPhone 11 físico (Kotlin/Native), 3 leituras A FRIO (client novo cada), sobre WiFi:**
```
run1 ok=true connect=177ms ttfb=300ms download=768ms verify<1ms total=1620ms verified
run2 ok=true connect=136ms ttfb=334ms download=735ms verify<1ms total=1585ms verified
run3 ok=true connect=128ms ttfb=313ms download=758ms verify<1ms total=1582ms verified
```
Mediana total **1585 ms**; TTFB ~313 ms; verify sub-ms (SHA/Ed25519 acelerados no A13).
O device discou o IP público da VPS, fez handshake Noise, resolveu, baixou 768 KiB e
**verificou Ed25519 + sha-256 no próprio device** — capítulo íntegro reconstruído.

⚠ **CLASSE DE EVIDÊNCIA:** esta corrida foi sobre **WiFi (rede residencial do DEV)** — é prova
FUNCIONAL do E2E Native on-device (Q3/Q4), **não** o número de campo da 4.1, que exige **rede
móvel** (egress celular, não pela rede do DEV — regra 4.3). O número de rede móvel é medido a
seguir, com o iPhone em USB (controle) + WiFi desligado (tráfego por dados móveis).

---

## 4.1 / 4.3 — CÉLULA 1: E2E a frio no iPhone em REDE MÓVEL + auditoria  [executado · 2026-07-05 09:36]

Setup: iPhone no USB (só controle/console), **WiFi DESLIGADO** → tráfego do app por dados
móveis. App leitor Native disca o IP público da VPS (6070), resolve, baixa 768 KiB e verifica
no device. Auto-report de cada amostra ao coletor da VPS (6071), que registra o IP de origem.

**5 leituras A FRIO (client novo cada) sobre rede móvel — saída literal do device:**
```
run1 ok=true connect=144ms ttfb=311ms download=762ms verify<1ms total=1592ms verified
run2 ok=true connect=150ms ttfb=273ms download=787ms verify<1ms total=1569ms verified
run3 ok=true connect=126ms ttfb=286ms download=700ms verify<1ms total=1449ms verified
run4 ok=true connect=126ms ttfb=283ms download=703ms verify<1ms total=1450ms verified
run5 ok=true connect=131ms ttfb=281ms download=708ms verify<1ms total=1453ms verified
```
Medianas (n=5): **total=1453 ms**, ttfb=283 ms, download=708 ms, connect=131 ms,
verify sub-ms (rounds to 0 em ms inteiros; SHA-256/Ed25519 acelerados no A13). Contra os
limiares a priori: leitura **1453 ms < 15 000 ms** ✓ · TTFB **283 ms < 5 000 ms** ✓ ·
verify **<1 ms < 100 ms** ✓.

**Auditoria de não-truque (4.3):** o coletor da VPS registrou o **IP de origem** das 5 leituras
como `187.43.188.226`; o IP público residencial do DEV (este Mac) é `177.203.17.5`.
```
187.43.188.226 → AS4230  CLARO S.A.  (operadora móvel)     ← egress das leituras
177.203.17.5   → AS8167  V.tal        (banda larga do DEV)  ← NÃO usado
```
Redes/ASNs distintos → o egress foi pela **Claro (celular)**, não pela rede do DEV. Device
físico (via devicectl/USB só p/ controle), IP público da VPS, sem loopback/adb reverse/
simulador. Três redes realmente separadas na jornada: DEV(residencial, publica/serve) —
VPS(datacenter, IP público) — iPhone(Claro móvel, lê). Classe: **[executado]**.

---

## 4.2 — Baseline Moto g30 (Kotlin/JVM/ART)  [executado · 2026-07-05]

App `:pocs:poc07:android` (AGP 9 com Kotlin embutido; alvo Android de KMP via o plugin novo
`com.android.kotlin.multiplatform.library`) roda o MESMO `ReaderProbe` de commonMain no Moto
g30, sobre a rede do device (hotspot do iPhone → cellular Claro; "Metered: true"). 5 leituras
a frio, Logcat:
```
run1 ok=true connect=388ms ttfb=289ms download=1270ms verify=8ms total=2465ms verified
run2 ok=true connect=213ms ttfb=523ms download=2274ms verify=6ms total=3692ms verified
run3 ok=true connect=153ms ttfb=245ms download= 977ms verify=6ms total=1704ms verified
run4 ok=true connect=160ms ttfb=251ms download= 912ms verify=8ms total=1667ms verified
run5 ok=true connect=158ms ttfb=208ms download= 979ms verify=7ms total=1696ms verified
```
Mediana total **1704 ms**; verify 6–8 ms (Moto sem SHA em hardware; ainda < 100 ms).
**A SPI portada não regrediu o caminho JVM/Android:** o MESMO `commonMain` fecha o E2E
verificado em 3 plataformas — host JVM **779 ms**, Moto ART **1704 ms**, iPhone Native
**1453 ms** (rede móvel). Classe: **[executado]**.

**Achado de porte (Android):** o provider JDK da cryptography-kotlin não deriva pubkey Ed25519
da privada no Android — o `org.bouncycastle` **stripped** embutido no Android sombreia o bcprov
completo do app. Resolvido no design do LEITOR (não é regressão): o leitor usa a pubkey CONHECIDA
do publicador (âncora de confiança, constante — como um app real embarca) e a idHex do bootstrap,
sem derivar de semente. Assinar (derivar) só o publicador/servidor (JVM, com BC completo) faz.

## 4.4 — Célula 1 registrada e commitada  [executado · 2026-07-05]

---

## 5.1 — CÉLULA 2: mecanismo cinterop C-ABI ao rust (NOVO vs JNI/UniFFI)  [executado (build) · 2026-07-05]

O binding de Rust para **Kotlin/Native é cinterop a header C + staticlib** — mecanismo
diferente do JNI/UniFFI que o Android usou (poc-03/04). Provado com um facade C-ABI mínimo:
- **Cross-compile:** `cargo build --release --target aarch64-apple-ios` → `libpoc07cffi.a`
  (16 MB). Rust 1.91, target `aarch64-apple-ios` instalado.
- **Símbolos C-ABI exportados** (`nm`): `_poc07_cffi_add`, `_poc07_cffi_sha256_hex`,
  `_poc07_cffi_free` (todos `T`, texto/exportados).
- **cinterop:** `.def` (headers=poc07cffi.h, staticLibraries=libpoc07cffi.a) → task
  `cinteropPoc07cffiIosArm64` SUCCESSFUL → klib gerado; Kotlin/Native chama o Rust por
  `import poc07cffi.*` (`FfiProbe.ios.kt`, com `usePinned`/`reinterpret`/`toKString`).
- **Link:** `linkDebugFrameworkIosArm64` embute o `.a` no framework `Probe` e o app do device
  compila+linka (BUILD SUCCEEDED) — o `.a` Rust está dentro do binário iOS assinado.

**Pendente (device):** a EXECUÇÃO on-device do cinterop (chamar `Ffi.run()` no iPhone e conferir
`rust_sha256(opentoons-poc07)` = `4054fad14f51ca5f02f7d78feaca485c879c15ebe7afd00c66c20d4c0bdd00f5`)
ficou bloqueada pelo túnel `devicectl` indisponível com WiFi desligado (só USB não subiu o
tunnel nesta config). Não é falha do binário — o app linkou; falta a janela de deploy.

**Custo de porte medido até aqui (o eixo Q5):** a Trama cruzou para iOS Native como **Kotlin
puro em `commonMain`** (troca de lib de crypto + socket, ZERO FFI) e fechou TCK+E2E real. O
libp2p exige **cross-compile de um `.a` rust + C-ABI + cinterop** (mecanismo novo, cadeia de
build extra) — provado viável no nível de binding, porém com custo de integração
estruturalmente maior que o da Trama. O backend libp2p COMPLETO (satisfazer a P2pBackend +
TCK verde + E2E) não foi construído nesta rodada — é um esforço grande (cross-compile de todo
o rust-libp2p 0.54 p/ iOS, historicamente sensível em deps como ring/aws-lc).

### 5.1 (execução) — cinterop C-ABI ao rust EXECUTA em Kotlin/Native  [executado · 2026-07-05]

O túnel `devicectl` ficou indisponível (issue de ambiente, não do binário). A EXECUÇÃO do
binding foi então aferida no alvo **iosSimulatorArm64** (Kotlin/Native — D-rules: simulador
serve à prova de MECANISMO/gate, nunca a número de campo). Cross-compilei o `.a` também para
`aarch64-apple-ios-sim`; teste `FfiTest` (1/1 verde):
```
testsuite FfiTest tests=1 failures=0 errors=0   (iosSimulatorArm64, Kotlin/Native)
POC07-FFI add=42 rust_sha256(opentoons-poc07)=4054fad14f51ca5f02f7d78feaca485c879c15ebe7afd00c66c20d4c0bdd00f5
```
`add=42` prova a chamada C-ABI; o sha256 do Rust bate BYTE-A-BYTE com o vetor calculado
independente (Python) → o binding EXECUTA e é interoperável, não só compila. Nota de toolchain:
com 2 alvos usando o MESMO cinterop, o commonizer desta versão (Kotlin 2.4.0) não expôs o
cinterop ao source set compartilhado; resolvido deixando o cinterop no alvo sim com `package =
poc07cffi` explícito no `.def`.

**Fechamento do mecanismo (célula 2):** cross-compile do `.a` rust p/ `aarch64-apple-ios`
(device) **e** `-sim`; símbolos C-ABI exportados; cinterop gera klib; **link** no binário iOS
assinado do device (BUILD SUCCEEDED) + **execução** byte-a-byte em Kotlin/Native (sim). O
binding NOVO (vs JNI/UniFFI do Android) está provado ponta a ponta. Classe: **[executado]**.


### 5.1 (execução ON-DEVICE) — cinterop C-ABI ao rust roda no iPhone físico  [executado · 2026-07-05]

Túnel devicectl voltou (device `connected` após replug USB). O MESMO binding executado no
iPhone 11 físico, saída literal do device:
```
POC07-FFI add=42 rust_sha256(opentoons-poc07)=4054fad14f51ca5f02f7d78feaca485c879c15ebe7afd00c66c20d4c0bdd00f5
```
Agora o mecanismo cinterop está provado NO DEVICE (não só no simulador): cross-compile + link +
**execução byte-a-byte no hardware real**. Classe: **[executado]**.

### 5.x (ATTEMPT real) — cross-compile do rust-libp2p INTEIRO p/ iOS  [executado · 2026-07-05]

Eu tinha DECLARADO que o backend libp2p completo era "esforço grande, sensível em deps como
ring/aws-lc". O usuário cobrou: tentei ou supus? **Tentei agora**, e a suposição estava ERRADA:
```
cd pocs/poc04/rust-facade   # o facade rust-libp2p 0.54 do poc-04 (libp2p+tokio+kad+noise+TCP+RR)
cargo build --release --lib --target aarch64-apple-ios
  → Finished (2 warnings, 0 erros)
```
- Artefato: `libuniffi_facade.a` = **52 MB**, `lipo -info` → `architecture: arm64`.
- `nm`: símbolos reais `libp2p_core::transport::...` presentes.
- `cargo tree --target aarch64-apple-ios`: **`ring v0.17.14` e `quinn v0.11.11` (QUIC)**
  compilaram para o alvo iOS — exatamente as deps que eu tinha apontado como risco.

**Correção honesta:** o rust-libp2p cross-compila LIMPO para `aarch64-apple-ios`. O muro que
supus não existe. O custo restante do backend libp2p→iOS é: (1) reescrever o facade
**UniFFI/JNI** (do Android) como **C-ABI** cobrindo a semântica do seam, e (2) o cinterop —
mecanismo que JÁ provei funcionar (link + execução no device). Não é impossível nem bloqueado
por toolchain; é trabalho de integração, mensurável. Isso REFINA (não inverte) o veredicto do
eixo: a Trama ainda vence por ser `commonMain` puro (0 FFI), mas o libp2p→iOS é MAIS barato do
que eu havia sugerido — o cross-compile, que era a incógnita, está resolvido.

### 6.1 (ATTEMPT real) — I2P-no-iOS: tentei em vez de só declarar  [dado-só · 2026-07-05]

Cobrança justa do usuário: no design "se travar, registrar inviabilidade" — eu tinha declarado
sem tentar. Tentei agora (bounded):
- `git clone PurpleI2P/i2pd`: o **README lista iOS como alvo oficial** (docs de build iOS em
  i2pd.readthedocs.io/.../building/ios/).
- `libi2pd/` expõe **`api.h` + `api.cpp` + `Streaming.h/.cpp` + `Destination.*`** → o i2pd tem
  **API C++ embutível in-process** (não precisa de daemon separado nem de SAM localhost).
- Deps: OpenSSL + Boost + Zlib (static). O OpenSSL do brew local é **macOS-host arm64**, não
  `iphoneos` — plataforma errada; `cmake` não está instalado nesta bancada.

**Veredicto honesto (corrige o "me acovardei"):** I2P-no-iOS **NÃO é impossível** — é
oficialmente portável e embutível, e o binding seria o MESMO cinterop C-ABI já provado
(link+execução no device). A parede concreta é **cross-compilar o encadeamento C++
(OpenSSL/Boost/Zlib) para o SDK iOS** (esforço grande, porém conhecido/mensurável, não um muro
de linguagem). O gate GENUÍNO que o design temia é o modelo **App Store / background** de um app
PUBLICÁVEL — não "um túnel disparar num device de dev". Por isso o transbordo para poc-08 se
mantém: é stretch (mede 2 variáveis) e o buraco real é de plataforma-de-distribuição, não de
compilabilidade. Classe: **[dado-só]** (avaliação por artefato/observação; sem cronômetro/E2E).

---

## 5.2/5.4 — CÉLULA 2: libp2p E2E REAL no iPhone (não "só compilou")  [executado · 2026-07-05]

Cobrança do usuário: a célula 2 só tinha compile, não E2E. **Fechado de verdade agora.** Construí
o backend libp2p COMPLETO e rodei o E2E no iPhone físico:
- **Servidor (VPS):** binário rust `poc07-lp-server` (reusa o ServerNode do facade poc-04,
  rust-libp2p 0.54) compilado NA VPS (instalei rust+build-essential lá), servindo o MESMO
  capítulo de 768 KiB (arquivos gerados pela CampaignVectors, manifesto assinado + blocos por
  CID). `LP-SERVER-UP peer_id=12D3KooWKMyZ4AWMC7GzMutPoYgVmd8ih8rgTEAMstynaWaMKvXG` na 6080,
  Kademlia providing, IP público 143.95.220.165.
- **Cliente (iPhone):** `Libp2pBackend : P2pBackend` (Kotlin/Native) chamando o `.a` rust-libp2p
  por cinterop C-ABI (`poc07_libp2p_facade`, 53 MB). Link exigiu `SystemConfiguration.framework`
  (quinn/if-watch detecta interfaces). Mesmo `ChapterVerifier` fora do seam.

**3 leituras a frio no iPhone, saída literal do device (verify no device):**
```
lp1 ok=true connect=31ms ttfb=338ms download=984ms  verify<1ms total=2085ms bytes=786432 verified
lp2 ok=true connect= 1ms ttfb=361ms download=968ms  verify<1ms total=2029ms bytes=786432 verified
lp3 ok=true connect= 1ms ttfb=369ms download=1029ms verify<1ms total=2099ms bytes=786432 verified
```
O iPhone discou o servidor libp2p da VPS, **resolveu via Kademlia**, baixou 768 KiB por
**request-response** e **verificou Ed25519+sha256 no device**. Mediana total **~2085 ms**.

- **Coexistência (5.2):** MESMO binário/execução rodou 5 leituras Trama (run1-5) + 3 libp2p
  (lp1-3), todas verified → dois backends satisfazendo a MESMA `P2pBackend`, selecionáveis por
  código, coexistindo no mesmo app iOS.
- **Egress celular (não-truque):** VPS registrou as libp2p com `src=187.43.188.226` (Claro/
  AS4230) ≠ IP do DEV → E2E libp2p também por rede móvel real.

**Comparação REAL do eixo (E2E medido, não suposto):** Trama mediana **~1453 ms** × libp2p
**~2085 ms** no MESMO iPhone/capítulo/VPS, ambas verificadas, egress Claro. libp2p ~40% mais
lento AQUI + exigiu toda a cadeia cross-compile+C-ABI+cinterop+SystemConfiguration; a Trama é
`commonMain` puro (0 FFI). Classe: **[executado]**.

### 5.3 — Correção sobre libp2p no iOS: rejeição de adulteração no device  [executado · 2026-07-05]

O cenário-chave do TCK (bloco adulterado → rejeitado) provado sobre o transporte libp2p, no
iPhone físico. Adulterei 1 byte de um bloco no servidor libp2p da VPS e reiniciei; o leitor iOS
(libp2p) baixou e o `ChapterVerifier` REJEITOU no device:
```
lp1 ok=false ... detail=verify=BlockHashMismatch(cid=31a1f9dea0169551092d05e8bf4a446228c8c3eb4c9b713c66adcb7fd53c89be)
lp2 ok=false ... BlockHashMismatch   lp3 ok=false ... BlockHashMismatch
```
Com o bloco íntegro restaurado, volta a `verified` (§5.2). O verify é o MESMO `ChapterVerifier`
neutro (fora do seam) que já está TCK-verde com a Trama — aqui exercido sobre bytes trazidos
pela libp2p, no device. O verify é o MESMO `ChapterVerifier` neutro que já está TCK-verde com a
Trama — aqui exercido sobre bytes trazidos pela libp2p, no device. Classe: **[executado]**.

### 5.3 (completa) — TCK libp2p COMPLETO montado no alvo iOS (topologia de 3 nós)  [executado · 2026-07-06]

Fecha a ressalva anterior (a topologia TCK completa da libp2p não tinha sido montada). Estendi o
facade C-ABI (`libp2p-facade/src/lib.rs`) com o **ServerNode** exposto por C-ABI (`poc07_lp_store_*`,
`poc07_lp_server_new/bootstrap/start_providing`) e cross-compilei o `.a` também para
`aarch64-apple-ios-sim`. O `Libp2pTckTest` (novo, em `iosSimulatorArm64Test`) sobe **três full
nodes libp2p REAIS in-process, em loopback** — bootstrap (Kademlia server, store vazio) +
publicador (serve o capítulo do store; disca o bootstrap e `start_providing`) + cliente (Node;
disca o bootstrap, resolve por Kademlia, baixa por request-response) — e roda os cenários de
correção do poc-04 SOBRE libp2p de verdade. Saída literal (simulador bootado, Kotlin/Native):
```
[ RUN      ] Libp2pTckTest.resolveDescobreProviderEDownloadVerificaIntegro
TCK: bootstrap up peer=12D3KooWPqT2... port=53399
TCK: publicador anunciou a obra
TCK: resolve → provider='/ip4/.../quic-v1/p2p/12D3KooWLdJA...' (após 0ms)
[       OK ] resolveDescobreProviderEDownloadVerificaIntegro (2127 ms)
[       OK ] blocoAdulteradoERejeitado (2126 ms)       ← BlockHashMismatch
[       OK ] manifestoDeChaveErradaERejeitado (2127 ms) ← BadSignature
```
Portão via Gradle (`:pocs:poc07:trama:iosSimulatorArm64Test`, `standalone=false`): **BUILD
SUCCESSFUL** com `Libp2pTckTest tests=3 failures=0 errors=0` **e** `TramaTckTest tests=6
failures=0 errors=0` (sem regressão). Nota de toolchain: o runner padrão do KMP usa `simctl spawn
--standalone`, cujo sandbox restringe a rede do simulador e trava a resolução da DHT libp2p
(loopback UDP/QUIC); resolvido rodando no simulador **bootado** (`standalone=false`) — a topologia
libp2p precisa do loopback real. Cenários: descoberta/download/verify íntegro (Verified), bloco
adulterado (BlockHashMismatch), chave errada (BadSignature). push/expiry ficam de fora por serem
específicos da Trama — o leitor libp2p desta POC é read-only. Classe: **[executado]**.

### 4.2(audit) — Egress do Android auditado  [executado · 2026-07-05]

A VPS registrou o IP de origem das leituras do Moto g30 como `src=187.43.188.226` (Claro/AS4230,
via hotspot do iPhone) ≠ IP residencial do DEV `177.203.17.5` (V.tal/AS8167) → o Moto leu por
caminho celular (Claro), não pela rede do DEV. Mesmo rigor da auditoria iOS. Classe: **[executado]**.

### 6.2 (ATTEMPT concreto) — I2P-no-iOS: de-riscado ao máximo; E2E completo é build grande, NÃO impossível  [dado-só · 2026-07-05]

Não me acovardei desta vez — sondei o caminho in-process concretamente:
- **C++ do i2pd compila para o SDK iOS:** `xcrun -sdk iphoneos clang++ -c libi2pd/Base.cpp
  -target arm64-apple-ios15.0 -std=c++17` → **rc=0**. O código PORTA (não é muro de linguagem).
- **Embutível:** `libi2pd/api.h` + `Streaming.*` (API in-process, sem daemon/SAM localhost).
- **Mecanismo de binding:** o MESMO cinterop C-ABI que já provei ponta a ponta (libp2p).

**Parede concreta (não impossibilidade):** o E2E completo exige cross-compilar **OpenSSL +
Boost + Zlib para `iphoneos`** (mecânico, prebuilts existem), linkar o libi2pd, envolver
Streaming como C-ABI, subir um servidor I2P e rodar o leitor — tudo dispara tunnels I2P que
levam minutos. É **build grande** (horas), somado ao fato de a construção da rede I2P ser lenta.
Cada bloqueio CONCEITUAL foi de-riscado por teste concreto (C++ compila p/ iOS; API embutível;
cinterop funciona). **Diferente da libp2p, que FECHEI com E2E real, o E2E I2P NÃO foi
construído** — por esforço (cadeia C++ p/ iOS), não por impossibilidade. O gate GENUÍNO segue
sendo o modelo **App Store/background** de um app publicável → poc-08. Classe: **[dado-só]**.

### 6.2 (completa) — E2E de leitura sobre I2P no iPhone físico CONSTRUÍDO e VERDE  [executado · 2026-07-06]

O que era "build grande, não construído" foi **construído**. Um router **i2pd EMBARCADO
in-process** roda dentro do app iOS (Kotlin/Native + Swift), o MESMO mecanismo cinterop C-ABI da
libp2p — agora sobre o **libi2pd** (api.h + Streaming).

**Cadeia de build (arm64-apple-ios, ver `i2p-facade/scripts/`):**
- OpenSSL 3.5.0 → `libcrypto.a`+`libssl.a` (target `ios64-cross`).
- Boost 1.86 → `libboost_filesystem/program_options/atomic.a` (toolset darwin-iphone).
- libi2pd (i2pd 2.60.0, 47 fontes) → `libi2pd-ios-arm64.a` (clang++ arm64-apple-ios, `-DMAC_OSX`).
- wrapper C-ABI `poc07i2p.cpp` (start/ready/connect/send/recv) + libi2pd → `libpoc07i2p.a`.
- cinterop `poc07i2p` no `:trama` (iosArm64); OpenSSL/Boost/zlib/libc++ linkados no app (reader-ios).

**Servidor:** i2pd 2.60 com um **server tunnel** (destination I2P → TCP local 127.0.0.1:6090) e um
servidor de capítulo que serve o MESMO manifesto assinado + 3 blocos (768 KiB) por request-response.
Destination estável: `amw6j4ctypuz4pgfkrqvdmeko5cokfhwehtxfgh27l7imo2svyea.b32.i2p`. (Servidor no
Mac — a VPS ficou sem a chave SSH no agent durante a sessão; no I2P isso não é atalho: a destination
`.b32` só é alcançável PELA overlay I2P, nunca pela LAN — o leitor descobre por LeaseSet e streama
por túneis garlic-routed, sem caminho direto possível.)

**Leitor (iPhone 11 físico, Kotlin/Native):** o `I2pReaderProbe` sobe o router (reseed + tunnels +
netDB; pré-seed de 962 routerInfos p/ o boot ter peers conectáveis atrás do NAT), **DESCOBRE** a
destination pela netDB I2P (lookup de LeaseSet) e baixa o capítulo por um stream I2P, **verificando
Ed25519 + sha-256 no device**. 3 leituras a frio, saída literal do device:
```
i2p1 ok=true connect=1011ms ttfb=2844ms download=15245ms verify=17ms total=19122ms bytes=786432 verified
i2p2 ok=true connect=   0ms ttfb=2192ms download=14036ms verify= 5ms total=16235ms bytes=786432 verified
i2p3 ok=true connect=   0ms ttfb=2551ms download=19203ms verify= 5ms total=21761ms bytes=786432 verified
```
Mediana total **~19122 ms** (I2P é lento: 768 KiB garlic-routed por múltiplos hops; download ~14–19s,
ttfb ~2.2–2.8s). **verify sub-20ms no A13** — o capítulo íntegro reconstruído e verificado NO DEVICE
sobre I2P. Router pronto ~29s (com pré-seed do netDB). 

**Veredicto:** I2P-no-iOS **é tecnicamente viável e o E2E FUNCIONA** — router embarcado, descoberta e
transferência pela rede I2P, verify no device. Isto corrige o "transbordado por esforço": o E2E foi
construído. O gate que PERMANECE p/ poc-08 é o de **distribuição/plataforma** (modelo App Store +
execução em background de um app PUBLICÁVEL) — não a compilabilidade nem a viabilidade técnica, ambas
agora provadas. Nota: mede 2 variáveis (I2P + Native), como o design declarou. Classe: **[executado]**.
