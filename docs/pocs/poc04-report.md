# Relatório da PoC — poc-04 (abstração de P2P com backend trocável: Trama × rust-libp2p)

> Artefato durável do poc-04. O código em `poc04/` é descartável; o que vale é este relatório.
> Change: [openspec/changes/poc-04](../../openspec/changes/archive/2026-07-04-poc-04/proposal.md) · Design: [design.md](../../openspec/changes/archive/2026-07-04-poc-04/design.md)
> Linhas de base: [poc02-report.md](poc02-report.md) (stack própria "Trama") e [poc03-report.md](poc03-report.md) (rust-libp2p via UniFFI).

**Status: CONCLUÍDO** (jul/2026). Interface neutra + TCK escritos antes de qualquer adapter;
os DOIS backends (client **e** full node) implementados de verdade atrás do mesmo seam;
**TCK 100% verde nos dois; matriz E2E 8/8 células verdes** — incluindo dispositivo físico em
dados móveis (hotspot de outra operadora) → IP público, duas vezes, **só trocando a build
variant**; ponte dual-stack provada. Nenhum veto de esforço foi aproximado. Os limiares foram
fixados ANTES de qualquer medição (seção abaixo, commitada antes dos experimentos).

**Veredito de estratégia (Q11): `própria → rust-libp2p condicional a gatilho`** — ver a
seção final.

---

## Validação em VPS real (matriz E2E re-executada, internet)

> A matriz E2E foi **executada numa VPS real e separada** (`143.95.220.165`, IP público
> direto — sem NAT hairpin, sem co-localização), no mesmo padrão de rigor da poc-05. Nós
> plenos dos **dois backends** subiram na VPS (Trama JVM direto; rust-libp2p
> **cross-compilado para `x86_64-linux-gnu`** e carregado por JNA):
>
> - **Matriz 8/8 verde re-medida** (2 backends × {ok, bloco corrompido, chave errada}), com o
>   **Moto g(30) em dados móveis** (hotspot, rede da operadora) baixando da VPS pela clearnet e
>   o **desktop** por outra rede. Todos com **descoberta fria** (o cliente conhece só o
>   bootstrap) + transferência + verificação Ed25519.
>
>   | Backend | desktop→VPS (descoberta / ciclo 768 KiB) | device→VPS (dial / resolve / ciclo) |
>   |---|---|---|
>   | Trama  | 347–378 ms / 0,97–1,06 s | 636 / 1278 / **4579 ms** (786432 B) |
>   | rust-libp2p | 468–587 ms / 0,94–1,02 s | 51 / 1647 / **3010 ms** (786432 B) |
>
> - **Dual-stack (Q9)** re-provado na VPS: 1 processo, 1 blockstore, 2 `FullNode` com a MESMA
>   identidade Ed25519 — cliente Trama (475 ms) e cliente libp2p (410 ms) baixaram os **mesmos
>   786432 bytes** pela internet.
> - **Throughput real** node↔node pela internet: ~1 MB/s (768 KiB/ciclo) nos dois backends do
>   desktop; no device (dados móveis) o download efetivo ficou ~0,3 MB/s (Trama) / ~0,6 MB/s
>   (libp2p) descontando dial+resolve.
> - **Energia (bateria):** não reportada nesta PoC (nem nas de origem) — a medição adb/USB não
>   é confiável; o custo de rede é o tráfego por UID e as latências/throughput reais.
> - **iOS: FORA DE ESCOPO** (decisão do usuário). Registro de viabilidade colhido:
>   os **static libs do rust para iOS** (`aarch64-apple-ios` + `-sim`) **cross-compilaram OK** →
>   o backend **libp2p é o caminho nativo tratável no iOS** (static lib + Swift via UniFFI),
>   enquanto o Trama exigiria um port Kotlin/Native de cripto+socket (hoje preso a
>   BouncyCastle/`java.net`). Fica como dado de design: **iOS seria, ao contrário do Tor da
>   poc-05, um argumento a FAVOR do libp2p** — candidato a gatilho de migração.

## Questões Q1–Q12 (fixadas a priori) — respondidas

| # | Questão | Resposta | Evidência |
|---|---|---|---|
| **Q1** ⚑ | O seam de **full-node** unifica gossip e Kademlia? | **SIM.** `start`/`serve`/`publish`/`announce`/`stop` + `AnnounceTuning` (TTL/republish declarativos) cobriram os dois motores sem NENHUM método ou parâmetro específico; os 4 atritos do kad foram absorvidos dentro do adapter (ver E2) | executado (TCK 8/8 + matriz 8/8) |
| Q2 | Pontos de **vazamento** do `:api`? | **3, todos documentados; zero branch de app** (inventário abaixo): capability-flag `HOLE_PUNCH`; semântica divergente-documentada do `AnnounceTuning`; metadado de build da variant libp2p (jniLibs+JNA) | executado (auditoria por grep) |
| Q3 | Os **tipos neutros** seguram? | **SIM.** `ContentId` sha256 cruza como chave de request no libp2p (não foi preciso CID/multiformats); `BootstrapAddr` host+porta+pubkey vira multiaddr+PeerId DENTRO do adapter (PeerId derivado **da mesma chave Ed25519** — identidade unificada pela semente); `Provider` opaco carrega "idHex@host:porta" num mundo e multiaddr no outro sem o app saber | executado |
| Q4 | Fronteira **~grátis em peso**? | **SIM.** APK release+R8: **trama 0,92 MB** (baseline 0,96 → **-4%**); **libp2p 7,84 MB arm64 / 5,38 v7a / 8,52 x86_64** (baseline 8–11/ABI → abaixo). Um backend por APK confirmado por inspeção (0 .so/0 classes uniffi na trama; 0 classes trama na libp2p) | executado |
| Q5 | Curto prazo: a própria cobre 100% pela interface? | **SIM** — todas as células Trama verdes pela interface (TCK + S1–S4) | executado |
| Q6 | Que features caem **abaixo do seam**? | Migração **gossip→DHT: invisível** (o mesmo app resolveu via gossip e via Kademlia sem 1 linha de diff — as 8 células). **Hole-punch: abaixo do seam** (componentes relay_client+dcutr entram com a variant; flag consultável). **Browser/WebRTC: análise só-design** — assinaturas seguram (abaixo) | executado / dado-só / só-design |
| Q7 | O que a própria teria que **construir do zero**? | Hole-punch coordenado ≈ 3–6 semanas + relay perpétuo; DHT de produção ≈ 4–8 semanas + hardening perpétuo; QUIC/transportes ≈ meses (detalhe abaixo) | só-design (ancorado nos dados do poc-01/02) |
| Q8 | Feature só-libp2p a **0 linha de app**? | **SIM** — `capabilities={HOLE_PUNCH}` + dcutr/relay_client presentes na variant libp2p; src/main idêntico entre as variants por construção (flavors só têm o BackendProvider) | executado (nível build); hole-punch E2E: dado-só |
| Q9 | Migração **sem flag day** factível? | **SIM** — dual-stack: 1 processo, 1 blockstore, 2 FullNode (a MESMA identidade Ed25519 nos dois stacks); cliente-Trama e cliente-libp2p baixaram e verificaram os mesmos 786.432 bytes | executado |
| Q10 | **Gatilho** para percorrer `própria → libp2p`? | O gatilho do poc-02 mantido e agora operacionalizável: **≈ 5.000 registros ativos de provider** (custo O(n) do re-anúncio epidêmico) OU demanda real de hole-punch (leitores atrás de NAT sem port-forward) OU alvo browser. Percurso: full nodes viram dual-stack → apps rolam por update de variant → full nodes largam o stack antigo | — |
| **Q11** ★ | Estratégia? | **`própria → rust-libp2p condicional a gatilho`** (justificativa na seção final) | — |
| **Q12** ★ | O imposto da opcionalidade vale? | **SIM, com ressalva** — o imposto medido foi pequeno (adapter libp2p 147 LoC de cola; 0 mudança de interface; CI: cargo+NDK já pagos no poc-03), mas é um imposto PERPÉTUO (rust no CI + disciplina de não-vazar + upstream churn). O julgamento contra: se nenhum gatilho disparar até o Marco 4, o adapter vivo terá sido custo morto — aceitável porque o seam em si (a interface neutra) vale sozinho pela testabilidade (TCK) e já é o design do módulo de rede do Marco 2 | — |

Encadeamento cumprido: Q1 "sim" → Q6/Q7/Q9 deram o valor da opção → Q11/Q12 fecham.

## Limiares fixados a priori (design D5) × medições

| Métrica | Limiar | Medido | Passa? |
|---|---|---|---|
| Matriz E2E | 8/8 células verdes | **8/8** | ✅ |
| Ramificação por backend | 0 branches | **0** (auditoria por grep; únicos hits = comentários) | ✅ |
| Paridade de TCK | 100% nos dois | **4/4 Trama; 4/4 libp2p** (mesma suíte, mesmos vetores) | ✅ |
| Pontos de vazamento | ≤ 3, todos documentados | **3** (flag; config documentada; metadado de build) | ✅ |
| Peso trama | ≤ +5% vs 0,96 MB | **0,92 MB (-4%)** | ✅ |
| Peso libp2p | ≤ +5% vs 8–11 MB/ABI | **7,84/5,38/8,52 MB por ABI** (abaixo da baseline) | ✅ |
| Espessura do adapter | ≤ ~150 LoC de cola | **libp2p 147**; trama: cola ~35 (fábrica+conversões) — ver nota de método em LoC | ✅ |
| Feature abaixo do seam | 0 linha de app | **0** (variants compartilham src/main; flavor = 1 arquivo BackendProvider) | ✅ |
| Ponte dual-stack | bytes idênticos + assinatura OK | **786.432 B verificados nos dois clientes** | ✅ |
| Republish pós-morte | some e volta nos dois | Trama: expiry 9 s / volta 1 s; libp2p: expiry ≤ 10 s (TTL) / volta 1 s | ✅ |
| Descoberta transitiva | P nunca informado confirma conexão | Trama: `CONN noise:/187.43.188.226` (IP da operadora móvel!) + `REQUEST GET_MANIFEST`; libp2p: `CONN 12D3KooWRrhR…` + `REQUEST manifest_for=…` | ✅ |
| Veto de esforço | ≤ 5 dias úteis por adapter | **≪ 1 dia por adapter** (1 sessão para a PoC inteira) | ✅ |
| Latências (sanidade) | < 1 s / < 500 ms / ≤ 3 RTTs | abaixo (tabela em E5) | ✅* |

\* Um outlier de reconexão Trama (540 ms na 1ª amostra; demais 280–468 ms) — a reconexão
Trama é handshake Noise completo (sem resumption, como no poc-02); registrado, não mascarado.

## Matriz E2E 4×2 — 8/8 verdes (mesmo app, zero branch)

| Cenário | Trama | rust-libp2p |
|---|---|---|
| S1 — malha 4 nós (processos separados) + expiry/republish | ✅ anúncio propagou até R2; fetch verificado via R2; kill P → expiry 9 s; revive → volta em 1 s | ✅ mesma topologia/roteiro/asserções com Kademlia real (provide + republish); expiry ≤ TTL; volta em 1 s |
| S2 — capítulo real → Moto g(30) + verificação + 2 rejeições | ✅ 786.432 B em 3,1 s; BlockHashMismatch; BadSignature | ✅ 786.432 B em 2,7 s; BlockHashMismatch; BadSignature |
| S3 — descoberta transitiva (app conhece SÓ A) | ✅ P nunca informado descoberto e discado; P logou a conexão do app | ✅ idem (P logou CONN + REQUEST do PeerId do app) |
| S4 — internet real (dados móveis → IP público) | ✅ ciclo completo pelo caminho público | ✅ ciclo completo pelo caminho público |

**Rig do S2–S4:** full nodes no host anunciando `177.203.17.5` (port forwarding 4000–4999);
alcançabilidade externa comprovada por **check-host.net** (nós em FI/IN/IR/RS/UA/US/BR/CA/FR/SG/MD/RU
conectaram nas 4 portas: Trama 4400/4401, libp2p 4410/4411); Moto g(30) API 31 em **hotspot de
dados móveis** (rede da operadora, `187.43.188.226` visto pelo publicador — rede separada de
fato). Rejeições servidas por publicadores standalone com blockstore adulterado
(`TamperingBlockstore` — wrapper NEUTRO, nenhum mecanismo de adulteração dentro de backend) e
com manifesto de chave errada. O mesmo roteiro `-e mode fetch` nas duas variants; extras
idênticos exceto portas.

**Auditoria (6.6):** nenhuma célula usou simulação in-process (S1 = 4 processos JVM; S2–S4 =
dispositivo físico + processos de host), nenhum servidor fora da interface (o `publisher.rs`
avulso do poc-03 foi REMOVIDO do fork; todo full node é dirigido por `FullNode` via
NodeRunner — o mesmo driver neutro para os dois), nenhum `adb reverse` (o dispositivo está em
outra rede; adb só instalou APK e leu logcat), zero branch por backend (grep: os únicos hits
de "trama|libp2p" em consumidores são comentários).

## Registro de execução e atritos

### Setup (1.x)

- Toolchain do poc-03 revalidada com **zero atrito**: `cargo check` do fork 15 s; **alvo novo
  de host** = 1 comando (dylib macOS arm64 release: 7,7→8,3 MB); cargo-ndk 3 ABIs de primeira.

### E0 (2.x) — interface + TCK (escritos ANTES dos adapters)

- Seam: `P2pBackend` (client) + `FullNode` (servidor) + `Blockstore` (fonte de conteúdo
  NEUTRA fora dos backends — é o que viabiliza o dual-stack e a adulteração neutra do TCK).
- Acréscimo registrado à superfície mínima do design: `getManifest(provider, obra)` — neutro.
- Identidade unificada por **semente Ed25519** (`NodeKeys`): a Trama usa a pubkey crua como
  id; o adapter libp2p deriva o PeerId **da mesma chave** (`peer_id_from_ed25519`, interno ao
  facade). No dual-stack os dois stacks têm a MESMA identidade.
- Capability-flag (decisão 2.2): `Set<Capability>` consultável; 1 flag (`HOLE_PUNCH`).
- TCK: resolve transitivo + download verificado; rejeição de bloco corrompido; rejeição de
  chave errada; expiry→republish com morte/revive (TTLs curtos REAIS — 4 s/1 s — sem tempo
  virtual). Ajuste de topologia durante o E2: revive na MESMA porta (nó real reinicia na
  porta configurada) — neutro, vale para os dois backends.

### E1/E2 (3.x/4.x) — os dois adapters, client e full node

- **Trama:** refatoração honesta do poc-02 (Noise XX, RPC CBOR, PEX/RESOLVE/ANNOUNCE com TTL,
  malha por dials de saída — motor intacto), servindo do `Blockstore` ao vivo. **TCK 4/4 de
  primeira.**
- **libp2p:** facade estendido com `ServerNode` (listen TCP+QUIC, `Mode::Server`,
  `start_providing`, serve por Request-Response) + **`BlockstoreCallback`** — o servidor rust
  chama de volta o Kotlin A CADA request (callback interface do UniFFI 0.28; funcionou de
  primeira, inclusive rust→JVM a partir de thread do tokio). **Zero classe de bug FFI de
  host** (JNA + dylib) — a resposta à questão aberta do design: o host não revelou nenhuma
  classe de bug que o Android (poc-03) escondia.
- **4 atritos REAIS do rust-libp2p no lado servidor — todos absorvidos DENTRO do backend,
  zero mudança de interface** (a evidência central da Q1):
  1. **Provider records expirados são servidos para sempre**: a resposta de rede a
     GET_PROVIDERS (`provider_peers()`) não filtra `is_expired` (libp2p-kad 0.46.2 — o filtro
     só existe no lookup local) e o MemoryStore nunca purga. Correção: `ExpiringStore`
     (RecordStore custom que filtra expirados).
  2. **identify→kad não é automático**: sem a ponte manual (o kubo também a implementa), os
     kbuckets só conhecem portas efêmeras de conexões de entrada e o FIND_NODE devolve peers
     sem endereço discável. Correção: ponte nos dois loops (client e servidor).
  3. **`FoundProviders` não expõe os endereços do provider record** (só PeerIds) → resolve em
     2 passos (get_providers + get_closest_peers, herdado do poc-03), com "sem endereço com
     transporte" tratado como não-descoberto (o consumidor re-tenta; determinístico).
  4. **Ranking de endereços** (público > privado > loopback) no resolve — sem isso o S4
     entregaria `192.168.x` ao cliente de outra rede (os kbuckets ganham addrs de LAN via
     identify; o provider record em modo público carrega só o addr público).
- Custo do lado servidor rust: lib.rs 323 → 704 linhas (~380 novas, incluindo as correções).

### E3 (5.x) — features no tempo

- **5.1 gossip→DHT invisível (executado):** o mesmo consumidor (TCK, NodeRunner, app) resolveu
  via PEX/RESOLVE epidêmico e via walk de Kademlia sem 1 diff — `resolve(obraId)` escondeu o
  mecanismo nas 8 células. A migração de descoberta prevista pelo poc-02 é literalmente a
  troca de variant.
- **5.2 hole-punch DCUtR/relay (dado-só):** o teste device↔device sem port-forward NÃO coube
  no setup — exige um relay v2 SERVIDOR próprio em IP público (o facade tem relay **client**
  + dcutr wired, herdados do poc-03) e um segundo dispositivo atrás de NAT independente
  (emulador é NAT do host, não vale como evidência). Registrado como **dado-só com
  componentes wired**, mesma classe do poc-03.
- **5.3 abaixo do seam (executado no nível de build):** a variant libp2p ganha
  `capabilities={HOLE_PUNCH}` + dcutr/relay_client no motor com **0 linha de src/main
  alterada** (cada flavor contém exatamente 1 arquivo: BackendProvider). Auditável por
  construção: o APK trama nem contém o conceito.
- **5.4 browser/WebRTC (só-design):** as assinaturas seguram — `BootstrapAddr(host, porta,
  pubkey)` expressa um endpoint de signaling/wss; `Provider.addresses` são strings opacas
  (multiaddrs webrtc caberiam); `getManifest`/`getBlocks` não mudam. Um backend
  browser seria client-only (não implementa `FullNode`) — a interface já separa os dois
  papéis, então nada vaza. Ressalva honesta: bloqueio de runtime, não de assinatura — a
  Trama não teria transporte browser (WebSocket puro exigiria servidor de terminação), o
  rust-libp2p tem webrtc-direct; é exatamente o tipo de divergência que entraria como
  capability-flag.
- **5.5 o "construir do zero" (Q7, só-design ancorado em dados):**
  | Item | O que o libp2p já entrega | Custo estimado na própria | Classe |
  |---|---|---|---|
  | Hole-punch coordenado | relay v2 + DCUtR prontos (wired no facade) | protocolo de coordenação + detecção de NAT + keepalives + **infra de relay perpétua**: ~3–6 semanas de eng + operação contínua | só-design |
  | DHT de produção | Kademlia com provider records, republish, K-closest (e os quirks acima já mapeados) | o Kademlia enxuto do poc-02 tem 278 LoC, mas o real exige LRU com ping, expiração fina, proteção eclipse, jobs de republish — ~4–8 semanas + hardening perpétuo; os 2 bugs reais/dia achados nas sims do poc-02 e os 4 quirks do kad de referência mostram a densidade de armadilhas | só-design (ancorado poc-01/02/04) |
  | Transportes plugáveis (QUIC, WebRTC) | tcp+quic prontos; webrtc no ecossistema | QUIC em JVM = dependência pesada (netty-quic ≈ MBs) ou implementação própria (meses); WebRTC idem | só-design |

### E5 (7.x) — dual-stack + custo da abstração

- **7.1 dual-stack (executado):** 1 processo, 1 `MemoryBlockstore`, 2 `FullNode` (a MESMA
  identidade Ed25519); cliente-Trama: `VERIFICADO 786432 bytes`; cliente-libp2p:
  `VERIFICADO 786432 bytes` — bytes idênticos por verificação de hash+assinatura contra o
  mesmo manifesto. A rota de migração sem flag day existe de verdade.
- **7.2 peso (executado):** tabela nos limiares. Nota: as baselines do poc-03 eram R8 OFF;
  aqui release+R8 ON com keep de JNA/uniffi — por isso a variant libp2p ficou ATÉ menor que
  a baseline. O universal libp2p (20,2 MB) não é o artefato de distribuição (split por ABI).
- **7.3 LoC e esforço:**
  | Peça | LoC |
  |---|---|
  | `:api` (seam + tipos + verify + manifesto) | 215 (+ TCK 163) |
  | Motor Trama portado (wire/: Noise+RPC+TCP) | 511 |
  | Adapter Trama (FullNode 173 + Client 112 + fábrica 10) | 295 |
  | Adapter libp2p (Client 54 + FullNode 77 + fábrica 16) | **147** |
  | Facade rust (motor libp2p) | 704 (323 do poc-03 + ~380 do servidor) |
  | Driver de host neutro (NodeRunner + 3 mains) | 143 |
  | App (src/main neutro + 2 flavors) | 141 + 14 |
  **Nota de método:** no adapter libp2p a separação cola/motor é limpa (motor = rust +
  binding gerado): **147 LoC ≤ ~150 ✓**. No Trama o motor foi refatorado PARA DENTRO das
  classes do adapter (é a stack própria — não existe "motor de terceiro" para ficar de
  fora); a cola stricto sensu (fábrica + conversões Provider/BootstrapAddr) é ~35 LoC. O
  limiar foi pensado para medir o custo de ADAPTAR um motor existente — cumprido nos dois
  sob essa leitura, registrada aqui a interpretação.
  **Esforço real:** PoC inteira (E0→E5, 8 células, relatório) em **1 sessão de trabalho
  assistida** — nenhum veto (5 dias/adapter) sequer aproximado. Iterações de depuração no
  adapter libp2p: 3 (expiry; endereços/identify; ranking) — horas, não dias.
- **7.4 inventário de vazamento (a métrica-mãe):**
  | # | Ponto | Natureza | Localização |
  |---|---|---|---|
  | 1 | `Capability.HOLE_PUNCH` | capability-flag documentada (a admissão de que os backends divergem nessa capacidade) | `:api` Types.kt |
  | 2 | `AnnounceTuning` (ttl/republish) | config neutra com **semântica documentada-mas-divergente** (epidemia O(n) re-anuncia a cada rodada de malha × provider records K-closest com publication interval) — o app declara o quê, não o como; os números têm efeitos diferentes por backend | `:api` Types.kt |
  | 3 | Variant libp2p exige jniLibs + JNA @aar (e `jna.library.path` no host) | metadado de BUILD (grafo de dependências, D1) — nunca código de app | build.gradle.kts do app / scripts |
  **Total: 3 ≤ 3, zero branch de app → seam saudável (Q2 ✓).**
- **7.5 latências (sanidade, dispositivo em dados móveis → IP público):**
  | Métrica | Trama | libp2p | Limiar |
  |---|---|---|---|
  | Handshake frio (dial) | 323–475 ms | 9–88 ms* | < 1 s ✓ |
  | Reconexão (redial) | 280–540 ms (Noise completo, sem resumption) | 8–20 ms* | < 500 ms ✓ (1 outlier 540 ms registrado) |
  | Lookup (resolve) | 579–1.115 ms (PEX+RESOLVE, 2 RTTs) | 1.003–1.105 ms (walk kad + FIND_NODE) | ≤ 3 RTTs ✓ |
  | Ciclo completo com download (768 KiB) | 3,1 s | 2,7 s | — |
  \* o dial do libp2p retorna no estabelecimento assíncrono da conexão (swarm), medido do
  lado do app; não é comparável 1:1 com o handshake Noise bloqueante da Trama — os ciclos
  completos (3,1 × 2,7 s) são a comparação honesta.

## Veredito de estratégia (Q10–Q12)

**Q11: `própria → rust-libp2p condicional a gatilho`.**

Racional:
1. **A própria continua vencendo hoje** (poc-02 revalidado): 0,92 MB de APK contra 7,8 MB/ABI,
   zero FFI, zero toolchain nativo no CI do app, e cobriu 100% do ciclo do produto pela
   interface (Q5).
2. **O seam segura de verdade** (Q1–Q3): não é mais uma aposta — client E full node dos dois
   mundos rodaram atrás da MESMA interface com 3 pontos de vazamento documentados e zero
   branch. O custo de manter a opção aberta caiu para ~150 LoC de cola por backend + a
   disciplina do TCK.
3. **O que o libp2p compra "depois" é caro de construir** (Q7: hole-punch + DHT de produção +
   transportes ≈ meses de eng + infra/hardening perpétuos) e **já está de-riscado** (poc-03 +
   o lado servidor desta PoC, com os quirks do kad mapeados e corrigidos no adapter).
4. **A migração não tem flag day** (Q9): dual-stack provado — full nodes primeiro, apps por
   update de variant, desligamento do stack antigo por último.

**Q10 — gatilho concreto e observável** (qualquer um dispara a migração de descoberta, via
dual-stack):
- `obras × réplicas` > **≈ 5.000 registros ativos de provider** (métrica do poc-02: custo do
  re-anúncio epidêmico ≈ 18 KB/h/nó por registro com refresh de 1 min — monitorável em
  produção pelo tráfego de ANNOUNCE por nó); OU
- demanda real de **hole-punch** (leitores que não conseguem alcançar full nodes públicos —
  observável por taxa de falha de dial); OU
- decisão de produto por **leitor browser** (transporte que a própria não tem).

**Q12 — o imposto vale?** Sim, com honestidade sobre o contra: o adapter libp2p vivo custa
rust+NDK no CI, o churn do upstream (o poc-04 já absorveu 4 quirks do kad 0.46; versões
futuras moverão queijo) e a disciplina permanente de não-vazar (TCK + auditoria de branch
como gate). Se nenhum gatilho disparar, esse custo terá comprado só opcionalidade. O
julgamento é que vale mesmo assim, por dois motivos: (a) o instrumento que mantém a opção
honesta — interface neutra + TCK — é o mesmo que torna a camada de rede testável e já é a
recomendação de design do Marco 2 independentemente de backend; (b) o item mais caro (lado
servidor rust via FFI) já foi pago aqui e está documentado. A alternativa "commit na própria
e assumir o acoplamento" economizaria só a fatia CI/upstream do imposto — e reintroduziria
o risco de reescrita que o poc-02 documentou.

**Recomendação para o Marco 2 (design do módulo de rede):** adotar o seam desta PoC como
contrato do módulo de rede (reescrito nos módulos de produto com revisão, como manda a
proposta): `P2pBackend`/`FullNode`/`Blockstore` + tipos neutros + verify fora do seam + TCK
como suíte de conformidade no CI; backend de lançamento = **Trama**; adapter rust-libp2p
mantido verde no CI (host dylib basta — o TCK não precisa de dispositivo) até um gatilho da
Q10 disparar ou o Marco 4 rediscutir.

## Ameaças à validade (registradas)

- Malhas pequenas (4 nós): escala de DHT/gossip não foi re-medida (é papel das sims do
  poc-02, não desta PoC).
- `dial` assimétrico entre backends na tabela de latência (nota acima).
- Expiry do libp2p no S1 observado no limite do TTL (janela entre republishes); o TCK cobre
  o caso com asserção estrita.
- Dados de sessão longa não re-medidos aqui (non-goal; o wire de ambos os backends é o mesmo
  das POCs de origem). **Energia (bateria) não é reportada em nenhuma PoC** — a medição adb/USB
  não é confiável; a decisão de stack não depende dela.
- "1 sessão" de esforço é implementação assistida (mesma ressalva do poc-02).
