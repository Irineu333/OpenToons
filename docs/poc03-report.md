# Relatório da PoC — poc-03 (libp2p de referência via bindings nativos)

> Artefato durável do poc-03. O código em `poc03/` é descartável; o que vale é este relatório.
> Change: [openspec/changes/archive/2026-07-03-poc-03](../openspec/changes/archive/2026-07-03-poc-03/proposal.md) · Design: [design.md](../openspec/changes/archive/2026-07-03-poc-03/design.md)
> Linhas de base: [poc01-report.md](poc01-report.md) (nabu/jvm-libp2p) e [poc02-report.md](poc02-report.md) (stack própria).
>
> **Follow-up (poc-04):** o facade rust deste relatório foi **estendido com o lado
> full-node via FFI** (listen + Kademlia server + provide + serve-blocks por callback
> Kotlin) e posto atrás do mesmo seam neutro que a stack própria — matriz E2E 8/8 e
> veredito `própria → rust-libp2p condicional a gatilho`. Ver
> [poc04-report.md](./poc04-report.md).

**Status: CONCLUÍDO** (jul/2026 — E1–E5 executados; ressalva única: bateria não medível no rig
adb/USB, ver 8.1). O poc-03 é um benchmark comparativo do libp2p **de referência** (go-libp2p e
rust-libp2p) via
**bindings nativos** — o buraco que o poc-01 (port JVM capenga) e o poc-02 (venceu esse
adversário) deixaram. Ambas as variantes foram do binding ao **E2E do Marco 0 pela internet real**
(device em dados móveis → IP público → descoberta fria → download → verificação → rejeição de
adulterado) no Moto g(30). O produto é **conhecimento comparável**, não decisão de stack (design
"Why"/"Goals"). Os limiares abaixo foram fixados ANTES de qualquer medição.

Esta é a via mais custosa das três POCs (as anteriores eram Kotlin puro); o design fixou um
**veto de esforço de 5 dias úteis por variante** (D5) que transforma estouro de custo em
resultado válido. Este relatório registra o esforço real por experimento.

---

## Limiares fixados a priori (design D5)

Definidos ANTES de qualquer medição. Ajustes posteriores exigem justificativa registrada aqui.

| Métrica | Cenário | Limiar |
|---|---|---|
| Bateria | Sessão do poc-01/02: 30 min, lookups periódicos, Moto g(30) | **< 5%** |
| Dados móveis | Mesma sessão, tráfego do UID além do conteúdo | **< 20 MB** |
| Handshake | Primeira conexão, dispositivo físico, rede real | **< 1 s** |
| Reconexão | Reconexão ao mesmo nó (QUIC 0-RTT se houver) | **< 500 ms** |
| Lookup frio (Kademlia) | Cliente resolve providers a partir só do bootstrap | **≤ 3 RTTs** |
| APK por ABI | App completo com o binding, split de ABI (vs 0,96 MB do poc-02) | **≤ 20 MB por ABI** |
| Veto de esforço | Cada variante do E1 (go; rust) até o E2E no dispositivo | **≤ 5 dias úteis** cada |

**Dados só-coletados (sem limiar — o ponto é colher):** hole-punch DCUtR device↔device sem
port-forward (TCP); estabilidade de QUIC em dials paralelos; interop com kubo local (bônus
só-go). O limiar de APK reconhece que o binding é intrinsecamente mais pesado que Kotlin puro:
os **0,96 MB do poc-02 não são atingíveis**; `≤ 20 MB por ABI` é o teto de "shippable" com
split de ABI, e o delta bruto vs 0,96 MB é o custo honesto do caminho de referência.

### Baselines das POCs anteriores (para as tabelas lado a lado)

| | nabu / jvm-libp2p (poc-01) | stack própria (poc-02) |
|---|---|---|
| Bateria (30 min) | ≈ 0,03% | ≈ 0,012% |
| Dados além do conteúdo | ≈ 1,09 MB | ≈ 0,13 MB |
| APK | 12 MB (debug, sem R8) | **0,96 MB** |
| Stack | framework pronto (JVM) | Kotlin puro (Noise XX + RPC próprio) |

---

## Prontos Tier A avaliados e descartados (design D1, tarefa 1.4)

Registrado ANTES de qualquer medição, como a Amino foi no poc-01: atalho avaliado e por que
não seguimos.

| Pronto | O que é | Por que NÃO adotado |
|---|---|---|
| **gomobile-ipfs** (berty) | Empacota o **kubo/IPFS inteiro** num `.aar` mobile | Pesado e opinativo: traz o nó IPFS completo, não o facade mínimo de 4 chamadas. O custo do facade (LoC, build) é justamente o **dado do E2** — importar o IPFS inteiro apagaria essa medição e inflaria o APK muito além do teto. |
| **iroh-ffi** | Bindings mobile do iroh (stack QUIC própria, não libp2p cru) | Não é o libp2p de referência: é outra implementação. Fora do escopo da comparação go-libp2p × rust-libp2p. |

Conclusão de D1: **Tier B** (gerador de binding pronto + facade fino nosso) — nós donos da
superfície (`dial`/`resolve`/`get-blocks`); o gerador cuida do marshalling. Tier C (JNI na
unha) reinventaria gomobile/UniFFI → descartado.

---

## E1 — Binding do libp2p de referência (2 variantes)

### 1.2 — Atrito de setup do toolchain (registrado por toolchain)

Ambiente: macOS (Darwin arm64), Android SDK em `~/Library/Android/sdk`, dispositivo físico
**Moto g(30), API 31** conectado.

| Componente | Estado inicial | Ação | Resultado |
|---|---|---|---|
| Go | 1.25.4 presente | — | ✓ |
| gomobile | ausente | `go install golang.org/x/mobile/cmd/gomobile@latest` (~11 MB) | ✓ instalado |
| gomobile init | — | com `ANDROID_NDK_HOME` | ✓ |
| Rust / cargo | 1.91.1 presente | — | ✓ |
| Targets Android (rust) | ausentes | `rustup target add aarch64/armv7/x86_64-linux-android` | ✓ |
| cargo-ndk | ausente | `cargo install cargo-ndk` (4.1.2) | ✓ |
| **Android NDK** | **ausente** | cmdline-tools (153 MB) → `sdkmanager "ndk;28.2.13676358"` (r28c) | ✓ **2,9 GB** |

**Atrito real registrado:** o SDK vinha **sem `cmdline-tools`/`sdkmanager` e sem NDK** — foi
preciso baixar cmdline-tools (153 MB) e o NDK (r28c, **2,9 GB descompactado**). A primeira
tentativa **falhou por falta de espaço em disco** (< 1,5 GB livres; o NDK precisa de ~2,5 GB);
resolvida após liberar espaço. O único NDK "mais novo" listado era **beta (r30)** — escolhido
o **r28c estável** para comparabilidade. Esse peso de setup (≈ 3 GB de toolchain nativo) é o
custo de entrada do caminho de binding que nenhuma POC anterior (Kotlin puro) pagou.

### Facades escritos (tarefas 2.1, 3.1) e validados no host

Antes do cross-compile, os dois facades foram **compilados no host** para validar a fonte:

| Variante | Fonte | LoC (não-trivial / total) | Host build |
|---|---|---|---|
| E1a — go-libp2p (`poc03/go-facade/facade.go`) | go-libp2p v0.38.1 + kad-dht v0.28.1 | **193 / 263** | `go build` + `go vet` ✓ limpos |
| E1b — rust-libp2p (`poc03/rust-facade/src/lib.rs`) | libp2p v0.54 (tcp/quic/noise/yamux/kad/req-resp/dcutr) | **304 / 362** | `cargo check` ✓ (1 warning benigno) |

Cola Kotlin do lado do app (`poc03/net`): **125 LoC** — a superfície FFI (`Libp2pFacade`) + a
verificação Ed25519/hash reusada do poc-01 (D7).

**Observações de esforço/ajuste ao KMP:**
- **rust/UniFFI:** `cargo check` do host achou erros reais e corrigíveis — `serde` a linkar,
  o codec `cbor` do `request-response` atrás de feature (`libp2p-request-response` com
  `features=["cbor"]`, resolvido por unificação de features do cargo), e `with_dns()` que é
  async (removido: discamos por IP no E4). Superfície async idiomática; o Swarm `!Sync` fica
  confinado a um ator Tokio, com as chamadas FFI comunicando por canal — padrão limpo para o
  `expect/actual` do KMP.
- **go/gomobile:** o facade expõe `*Node` opaco + métodos com tipos planos (`string`, `[]byte`,
  `error`) — compatíveis com `gomobile bind`. CIDs cruzam como string `\n`-separada e blocos
  como buffer length-prefixed, para o marshalling do gerador ficar trivial.

### Superfície FFI idêntica nas duas variantes (tarefa 4.1)

Verificado no nível de fonte/contrato: as duas variantes expõem **a mesma superfície** de 3
chamadas que cruzam a fronteira — `dial` / `resolve` / `getBlocks` — com **o mesmo wire de
bloco** (length-prefix uint32 big-endian, mesma ordem). A quarta operação do contrato,
`verify`, **não cruza a fronteira** (D7): a verificação Ed25519 do manifesto + hash de cada
bloco é feita em Kotlin (`ChapterVerifier`), o mesmo código para as duas variantes. Logo E2/E3/E4
rodam sobre qualquer uma sem mudar o app. (Verificação em runtime pendente do carregamento no
dispositivo.)

### 2.2 / 3.2 — Cross-compile por ABI ✓ (as duas variantes)

Ambas as variantes cross-compilaram para as 3 ABIs alvo. Tamanho do **binário nativo por ABI**
(a `.so` stripped é o que entra no APK):

| ABI | rust `.so` (cargo-ndk, stripped) | go `libgojni.so` (gomobile, stripped) | razão go/rust |
|---|---|---|---|
| arm64-v8a | **6,2 MB** | **~29 MB** | ~4,7× |
| armeabi-v7a | **3,9 MB** | **~29 MB** | ~7,4× |
| x86_64 | **6,8 MB** | **~29 MB** | ~4,3× |

- **rust:** `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 build --release` — um comando, três
  ABIs. Bindings Kotlin gerados por UniFFI a partir do `facade.udl`: **1 arquivo `facade.kt`
  (1552 linhas geradas)**, pacote `uniffi.facade`, runtime JNA. `.so` não-stripped: 9,4/6,5/9,3 MB.
- **go:** `.aar` único de **57 MB** com as 3 ABIs (`libgojni.so` ~40 MB não-stripped, ~29 MB
  stripped por ABI). O runtime Go + GC embutido é o peso — confirma a previsão do D2.

**Complexidade de build registrada — atrito real do gomobile (E1a):** o `gomobile bind` **falhou
na 1ª tentativa** com `link: github.com/wlynxg/anet: invalid reference to net.zoneCache`. Causa:
`anet` (dep transitiva do go-libp2p para enumerar interfaces de rede no Android) usa
`//go:linkname` para `net.zoneCache`, que o Go 1.23+ bloqueia por padrão (`-checklinkname`).
**Workaround:** `gomobile bind -ldflags="-checklinkname=0" ...` — resolveu. É um atrito
específico do caminho gomobile + go-libp2p em Go novo, que o cargo-ndk (rust) não teve.

> **Achado decisivo vs. limiar de APK (D5 = ≤ 20 MB/ABI):** o binário **go estoura o teto**
> (~29 MB/ABI só a `.so`, antes do resto do app), enquanto o **rust fica folgado** (3,9–6,8 MB/ABI).
> Contra os **0,96 MB do poc-02** (Kotlin puro), o delta bruto é ~6 MB/ABI (rust) e ~29 MB/ABI
> (go). Este é o custo honesto do caminho de referência e o primeiro desempate duro go × rust.

### 3.3 — Carga no dispositivo físico ✓ (rust) — o teste existencial passou

O `.so` do rust-facade foi carregado num app mínimo (`poc03/android`, JNA + binding Kotlin do
UniFFI) e **inicializou um nó libp2p real no Moto g(30), API 31 (Android 12), sem crash**:

```
poc3: NÓ INICIALIZADO SEM CRASH — peerId=12D3KooWLRRUZYkWvarAyM663vTMpsrkrAASVmWucnzhdDi6VeBh (init 94 ms)
poc3: E1 OK — binding rust carregado e nó fechado no dispositivo
```

`peerId` é um PeerId libp2p Ed25519 válido; init em **94 ms**. Isso **retira o risco existencial
do E1b (D8)**: facade escrito → cross-compilado → carregado no dispositivo-alvo → nó inicializa.

**Atrito de KMP do rust registrado no caminho até aqui** (custo real do UniFFI, dado do E1b):
1. **Construtor async não gera factory no backend Kotlin** do UniFFI 0.28 (`companion object`
   vazio; "no constructor generated as it is async"). Resolvido tornando o construtor **síncrono**
   (init bloqueia no runtime interno; `dial`/`resolve`/`get_blocks` seguem async — esses o Kotlin
   suporta).
2. **Método UDL `close()` colide** com o `close()` do `AutoCloseable` gerado. Resolvido removendo
   o `close` explícito (o AutoCloseable já dropa o objeto Rust → runtime encerra o ator).
3. **Nome de símbolo/biblioteca:** o UniFFI deriva os símbolos `ffi_`/`fn_` do **lib name** e os
   `checksum_` do **package name**; com `package name ≠ lib name` o `.so` exporta prefixos mistos
   e o dlopen/checksum do Kotlin falha (`undefined symbol …`). Resolvido alinhando
   **package name == lib name == `uniffi_facade`** (= `uniffi_` + namespace da UDL), que também é
   o nome de arquivo que o binding procura. Três iterações de build até o nó subir — atrito real
   do caminho UniFFI que o relatório registra para o CI do Marco 2.

### 2.3 — Carga no dispositivo físico ✓ (go) — também passou

O `.aar` do gomobile foi carregado num app mínimo (`poc03/android-go`, via `flatDir`) e
**inicializou um nó go-libp2p no mesmo Moto g(30) sem crash**:

```
nativeloader: Load .../arm64-v8a/libgojni.so ... ok
poc3go: NÓ GO INICIALIZADO SEM CRASH — peerId=12D3KooWAeaAdjU9t2NGnyxPyWa3EEpbNwU1VezvgdshH9kruZ3B (init 98 ms)
poc3go: E1a OK — binding go-libp2p carregado e nó fechado no dispositivo
```

**As duas variantes rodam no dispositivo-alvo**, com a mesma superfície (`newClientNode`/`peerID`/
`dial`/`resolve`/`getBlocks`/`close`), init equivalente (**go 98 ms, rust 94 ms**), sem crash.
Atrito da variante go: nenhum além do build (o gomobile gera uma API Java-ish direta e o `.aar`
é autocontido com `go.Seq` + `libgojni.so`; consumido por `flatDir`). Contra as **3 iterações de
naming/async** que o UniFFI exigiu, o gomobile foi mais direto **no app** — mas o `.aar` é ~6×
maior e falhou no link até o workaround `-checklinkname=0`.

APK debug (universal, sem R8): **rust 23 MB**, **go 93 MB** — o abismo de peso reaparece no app
inteiro. Split por ABI e delta fino com R8 ficam para o E5 (8.3).

---

## E2 — Superfície FFI + troca de blocos por Request-Response ✓ (as duas variantes)

Para o lado servidor (os facades são client-only por ADR-0005), escrevi **nós publicadores de
host** (`poc03/rust-facade/src/bin/publisher.rs` e `poc03/go-facade/cmd/publisher`): nó pleno que
escuta TCP+QUIC, roda Kademlia server, anuncia-se provider do obraId e serve o capítulo de teste
(manifesto assinado Ed25519 + 3 blocos). A **mesma seed de conteúdo (0x42)** nos dois → a **mesma
chave pública** (`2152f8d1…`), então o app verifica com a mesma chave em qualquer variante.

O dispositivo físico está em **outra rede** (dados móveis), então o E2 local usa `adb reverse`
(TCP) para o app alcançar o publicador no host. Resultado do download + verificação Kotlin (D7):

| Variante | Handshake | Download (5,4 KB / 4 blocos) | Verificação | Throughput |
|---|---|---|---|---|
| **rust** (cbor request-response) | 48 ms | 149 ms | **✓ assinatura + hashes** | 0,04 MB/s |
| **go** (stream length-prefixed) | 30 ms | 8 ms | **✓ assinatura + hashes** | 0,66 MB/s |

Throughput é dominado por RTT num payload de 5,4 KB (não comparável a granel); registrado sem
sobre-interpretar. O handshake **< 1 s** (limiar D5) folgado nos dois.

**5.2 — verificação Kotlin (D7), fora da fronteira FFI:** o app fatia os blocos length-prefixed
que a FFI entrega, verifica a assinatura Ed25519 do manifesto + o hash de cada bloco
(`ChapterVerifier`) e reconstrói o capítulo. Mesmo código nas duas variantes; também coberto por
**4 testes unitários verdes** (aceitação + 3 rejeições: hash divergente, assinatura inválida,
chave errada) — que já provam o critério de rejeição do E4/7.3.

**5.3 — fronteira FFI sob concorrência e lifecycle (rust/UniFFI):** rajada de **40 `getBlocks`
concorrentes** no mesmo nó → **40 ok / 0 falhas, sem crash** (517 ms); `close()` + recriação do
nó no mesmo processo → ok. **Nenhuma classe de bug FFI** (threading/memória/lifecycle) observada
no boundary do ator Tokio + oneshot.

**5.4 — bônus só-go, interop Bitswap com kubo real (`poc03/go-interop`, módulo isolado):** um nó
go-libp2p+`boxo/bitswap` **conectou a um kubo v0.32.1 local e buscou um bloco por Bitswap**
(want→block, 46 B dag-pb). É o único ponto em que a POC toca o ecossistema IPFS de fato — e
**fecha o gap que o poc-01 não fechou** (lá a Amino estava bloqueada por bugs do nabu). A variante
rust não tem Bitswap oficial (D3) e não é penalizada.

## E3 — Descoberta com Kademlia real na rede-bootstrap própria ✓

Rede-bootstrap própria (nós plenos rust, identidades determinísticas por porta): **nó A**
(bootstrap, obraId dummy) + **nó P** (provider real de `obra-teste-01`, bootstrapando em A).
O app conhece **só A + o obraId** — nunca o endereço de P.

- **6.2 — descoberta fria (Kademlia real):** o app conectou só em A, resolveu `obra-teste-01`
  via `get_providers` → **descobriu P** (`12D3KooWPGcbd…`), um nó que **nunca contatou**, e P
  confirma o CONN+REQUEST vindos do app. Discovery: **173 ms** (local) / **~900 ms** (internet).
  Numa rede pequena o lookup é ~1 hop (≤ 3 RTTs, ok), com a ressalva do tamanho da rede.
  **Achado de engenharia:** o `resolve` precisou de **2 passos** — `get_providers` (quem tem) +
  `get_closest_peers`/FIND_NODE (endereços de quem tem) — senão o cliente fica só com o PeerId e
  o request-response não sabe discar. Registrado como custo real da descoberta no rust-libp2p.
- **6.3 — client puro (ADR-0005):** por construção do facade — `NoListenAddrs` (não aceita
  entrada), `kad::Mode::Client` (consulta a DHT mas não a serve, não roteia, não guarda registros
  de terceiros).
- **6.4 — Amino (tentativa registrada):** um nó go-libp2p de referência **conectou a um bootstrap
  clássico da Amino** (`bootstrap ok: QmaCpD…`). Resultado **positivo** vs poc-01, onde a Amino
  estava bloqueada por 4 bugs upstream do nabu. Dado histórico, não critério.

## E4 — E2E do Marco 0 com o libp2p de referência ✓ (critério fechado, pela internet real)

- **7.1 — nó público alcançável:** A (4001) e P (4003) no ar via port forwarding; alcançabilidade
  externa **comprovada pelo check-host.net** (nós no mundo todo conectaram nas duas portas).
- **7.2 — E2E por outra rede:** o app no **Moto g(30) em dados móveis** (hotspot 172.20.10.x,
  rede distinta do host) conectou ao bootstrap A no **IP público** (177.203.17.5:4001),
  **descobriu P por descoberta fria** (Kademlia, ~900 ms), discou o publicador **nunca informado**
  no IP público, baixou manifesto+blocos e **verificou a assinatura Ed25519** (Kotlin, D7) —
  capítulo reconstruído (5400 B, download 215 ms). **Mesmo critério do Marco 0** que nabu (poc-01)
  e a própria (poc-02) fecharam, agora com o **libp2p de referência via binding nativo**.
- **7.3 — rejeição de adulterado:** contra um provider que serve um bloco com 1 byte corrompido,
  o app **rejeitou** (`BlockHashMismatch`), pela internet. (Assinatura inválida também rejeitada —
  coberto nos testes unitários.)

## E5 — Medições comparativas e dados só-coletados

**8.2 — latências (dispositivo em dados móveis, provider no IP público):**

| Métrica | Medido | Limiar (D5) |
|---|---|---|
| Handshake (1ª conexão) + 1º download | **206 ms** | < 1000 ms ✓ |
| Reconexão (conexão reusada) + download | **121–224 ms** | < 500 ms ✓ |

QUIC 0-RTT não foi isolado (o ganho de reconexão vem do reuso de conexão); registrado.

**8.3 — APK por ABI (release, R8 off) — o veredito de peso, com split de ABI:**

| ABI | rust (`.so` UniFFI) | go (`libgojni.so`) | teto D5 |
|---|---|---|---|
| arm64-v8a | **11 MB** ✓ | **33 MB** ✗ | ≤ 20 MB |
| armeabi-v7a | **8,1 MB** ✓ | **32 MB** ✗ | ≤ 20 MB |
| x86_64 | **11 MB** ✓ | **35 MB** ✗ | ≤ 20 MB |

Delta bruto vs **0,96 MB do poc-02** (Kotlin puro): **rust +7–10 MB/ABI**, **go +31–34 MB/ABI**.
LoC do facade: go 193, rust 304. Dependências diretas: go 6 (go.mod), rust ~8 crates (+ árvore
transitiva grande nos dois). **Conclusão dura: go é inshippável pelo teto de APK; rust cabe.**

**8.4 — dados só-coletados (sem limiar):**
- **Estabilidade sob dials paralelos:** a rajada de **40 `getBlocks` concorrentes** (E2/5.3)
  passou **0 falhas, sem crash** — evidência de estabilidade que o jvm-libp2p (poc-01) não teve.
- **QUIC:** transporte QUIC habilitado nas duas stacks (cliente e publicadores escutam TCP+QUIC).
- **Interop IPFS:** feito no 5.4 (go+boxo buscou bloco de um kubo real por Bitswap).
- **Hole-punch DCUtR (TCP):** `dcutr` + `relay_client` estão wired no facade rust; o teste
  device↔device sem port-forward (com relay) é setup maior — **componentes presentes, teste E2E
  de hole-punch não executado** (dado-só, sem limiar).

**8.1 — sessão de 30 min (mesmo roteiro do poc-01/02, Moto g(30), lookups periódicos):** ✓

```
59 lookups em 30 min — 59 verificados, 0 falhas
DADOS UID: rx 0,87 + tx 0,17 = 1,04 MB (limiar < 20 MB)
```

**Robustez:** 30 minutos ininterruptos do ciclo completo (descoberta fria Kademlia → download por
Request-Response → verificação Ed25519 em Kotlin) a cada 30 s, pela internet, **0 falhas em 59
ciclos**. Dados por UID lado a lado:

| | nabu (poc-01) | própria (poc-02) | **libp2p ref. (poc-03)** |
|---|---|---|---|
| Dados/30 min | ≈ 1,09 MB | ≈ 0,13 MB | **1,04 MB** ✓ (< 20 MB) |

O libp2p de referência gasta **~1 MB/30 min** — na faixa do nabu (DHT real + Request-Response por
ciclo), ~8× o da stack própria (que era mais enxuta). Bem dentro do teto.

**Bateria — não medível neste setup (ressalva honesta):** o dispositivo fica no **USB (carregando)**
para o controle adb, então o Δ% é 0 (100% → 100%) e o `batterystats` não estima drain sob carga
(tempo "on battery" ≈ 0). Uma medição de bateria comparável exigiria um run desplugado (sem adb),
fora do que este rig automatizado alcança. Dado o custo de dados equivalente ao nabu (que no
poc-01 mediu ≈ 0,03%/30 min) e o mesmo padrão de tráfego, a expectativa é da mesma ordem — mas
**não medido**, registrado como pendência de um run desplugado.

---

## Matriz comparativa go × rust (design D2) — parcial

Colunas medidas marcadas _pendente_ até o cross-compile/dispositivo. As demais vêm da doc atual
(Context7) e do design.

| Critério | E1a — go-libp2p (gomobile) | E1b — rust-libp2p (UniFFI) |
|---|---|---|
| Completude protocolar | Maior — Kademlia + **Bitswap via boxo** (é o kubo) | Kademlia sim; **sem Bitswap oficial** — blocos por Request-Response |
| KMP fit | `.aar` Java-ish; desktop `c-shared` por host | UniFFI → Kotlin idiomático (`facade.kt`, 1552 LoC ger.); `expect/actual` mais limpo ✓ |
| **Peso do binário/ABI (stripped)** | **~29 MB** (estoura o teto de 20 MB) | **3,9–6,8 MB** (dentro do teto) |
| Churn/atrito de build | falhou por `anet`/`checklinkname`; workaround `-ldflags` | ajuste de features (`cbor`) + `with_dns` async; sem falha de link |
| Interop ecossistema | kubo/IPFS de fábrica (bônus) | request-response próprio |
| Hole-punch | DCUtR TCP + QUIC | DCUtR TCP; QUIC no roadmap |
| LoC do facade | 193 | 304 |
| Host build / cross-compile 3 ABIs | ✓ / ✓ | ✓ / ✓ |

Hipótese a priori (do usuário): "go mais completo, rust melhor KMP". **Confirmada pelos dados até
aqui:** go traz mais protocolo (boxo/Bitswap) mas o binário é ~5–7× maior e **estoura o teto de
APK**; rust gera Kotlin idiomático, `.so` enxuto dentro do teto, ao custo de não ter Bitswap
oficial (irrelevante, pois a OpenToons usa Request-Response). O único desempate ainda aberto no
E1 é a **execução no dispositivo sem crash** (2.3/3.3).

---

## Questões abertas do design (respondidas)

- **Toolchain de menos atrito no CI?** Ambos exigem o NDK (~2,9 GB). **cargo-ndk** é um comando
  por N ABIs (`-t ... -t ...`), mas exige `--lib` quando o crate tem bins de host, e o UniFFI
  cobrou 3 iterações de naming/async. **gomobile** é `init` + `bind`, mais direto, mas falhou no
  link (`anet`/`checklinkname`) até o `-ldflags`. Empate em atrito, naturezas diferentes.
- **Facade com nó opaco de 4 chamadas ou handle de conexão?** Decidido: **nó opaco** com 3
  chamadas cruzando a fronteira (`dial`/`resolve`/`getBlocks`) + verify em Kotlin (D7). A
  superfície mínima segurou 40 chamadas concorrentes sem bug FFI. **Ressalva descoberta:** o
  `resolve` precisou de 2 passos internos (providers + FIND_NODE) para trazer endereços discáveis.
- **QUIC 0-RTT encurta reconexão < handshake?** Não isolado: a reconexão medida (121–224 ms) vem
  do **reuso de conexão**, não de 0-RTT puro. Ambos < 500 ms. Registrado como não-conclusivo p/ 0-RTT.
- **go/boxo faz interop real com kubo local?** **Sim** (5.4): go+boxo/bitswap conectou a um kubo
  v0.32.1 e buscou um bloco por want→block. Fecha o gap do poc-01.
- **Alvo iOS mínimo?** Fora do escopo desta POC (registrado); UniFFI daria o caminho mais curto
  (o mesmo `facade.udl` geraria bindings Swift).

---

## Esforço real (veto D5 = 5 dias úteis/variante)

Ambas as variantes chegaram do zero ao **nó rodando no dispositivo físico** dentro de **uma
sessão** (≪ 5 dias úteis) — **nenhum veto de esforço foi aproximado**. O bloqueio encontrado foi
ambiental (disco cheio na 1ª tentativa de NDK), não de esforço de engenharia. Esforço relativo:
o **rust** custou mais glue (3 iterações de naming/async do UniFFI) mas gera Kotlin idiomático e
`.so` enxuto; o **go** foi mais direto no app, ao custo de um workaround de link e ~6× de peso.

## Conclusão do E1 (o risco existencial do D8 — retirado)

**E1 está conclusivo e positivo para as duas variantes.** O caminho de binding (Tier B) é viável:
facades escritos → cross-compilados para 3 ABIs → **carregados no Moto g(30) API 31 → nó libp2p
inicializa sem crash** (go 98 ms, rust 94 ms), com a mesma superfície FFI. Desempates duros já
colhidos:

- **Peso (o ponto onde o poc-02 venceu):** rust `.so` 3,9–6,8 MB/ABI (dentro do teto de 20 MB);
  go `.so` ~29 MB/ABI (**estoura o teto**). APK debug: rust 23 MB × go 93 MB. Contra 0,96 MB do
  poc-02, o binding cobra caro — muito mais no go.
- **KMP fit:** rust (UniFFI) gera Kotlin idiomático; go (gomobile) é Java-ish e autocontido.
- **Atrito de build:** go falhou no link (`anet`/`checklinkname` → `-ldflags`); rust exigiu
  alinhar features/naming (3 iterações). Ambos registrados para o CI do Marco 2.

A hipótese a priori do usuário ("go mais completo, rust melhor KMP") **se confirma nos dados**.

## Conclusão geral (E1–E5 executados; sem veredito de stack — não é o objetivo)

O poc-03 **fechou o buraco**: o libp2p **de referência** foi medido de ponta a ponta, nas duas
variantes, no mesmo dispositivo e critério das POCs anteriores.

- **E1 — binding:** as duas variantes cross-compilam e **rodam no Moto g(30) sem crash**. Peso é
  o desempate duro: **rust 8–11 MB/ABI (cabe), go 33–35 MB/ABI (estoura o teto de 20 MB)**.
- **E2 — FFI + blocos:** download por Request-Response + verificação Ed25519 em Kotlin (D7) nas
  duas variantes; **40 chamadas FFI concorrentes sem crash**; **interop Bitswap com kubo real**
  (bônus só-go) — fechando o gap de ecossistema que o poc-01 não fechou.
- **E3 — descoberta:** **descoberta fria via Kademlia real** na rede-bootstrap própria (provider
  nunca contatado, resolvido em 2 passos providers+FIND_NODE); client puro por construção; nó de
  referência **conecta na Amino** (vs poc-01 bloqueado).
- **E4 — E2E do Marco 0:** **fechado pela internet real** — device em dados móveis → IP público →
  descoberta fria → download → verificação → **rejeição de adulterado**. Handshake 206 ms (< 1 s),
  reconexão 121–224 ms (< 500 ms).
- **E5 — comparação:** APK e latências acima; sessão de 30 min de dados/bateria (8.1) fecha a
  comparabilidade com poc-01/02.

**Nenhum veto de esforço foi aproximado** — as duas variantes foram do zero ao E2E numa sessão.
O custo do caminho de referência vs a stack própria (poc-02, 0,96 MB, Kotlin puro) é honesto e
medido: **~3 GB de toolchain nativo + 8–35 MB/ABI de binário**. A decisão de stack do Marco 2
**não é tomada aqui** — a POC entregou os dados comparáveis que eram seu único objetivo.
</content>
