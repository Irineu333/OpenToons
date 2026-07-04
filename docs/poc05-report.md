# poc-05 — modo anônimo (publicador sobre Tor), backend trocável: relatório

> **Status: 31/31 tarefas fechadas.** E0–E5 executados sobre Tor real nos dois backends; a
> matriz E4 (C1 + C2, com rejeições e auditoria de não-vazamento) fechada no Moto g(30) real
> em dados móveis. A parte a
> priori (D0, Q1–Q10, limiares D7) foi fixada **antes de qualquer medição** (task 1.5), como o
> design exige. Cada claim é etiquetada com a classe de evidência:
> **[executado]** (rodou nesta máquina, com saída observada), **[dado-só]** (número medido,
> não re-derivado), **[só-design]** (raciocínio de arquitetura, sem execução).

## 0. Definição de "anônimo" (D0 — fixada ANTES de medir) [só-design]

- **O que o modo PROTEGE:** o vínculo entre a _identidade Ed25519 do publicador_ (pública
  por design — assina tudo) e o _IP/localização do desktop_. É **pseudonimato com
  privacidade de rede**, não anonimato absoluto.
- **O que o modo NÃO promete** (limites honestos, no estilo do `overview.md`):
  - a VPS replicadora continua **pública e observável** (P4 do modelo de ameaças);
  - os leitores continuam em **clearnet** (o mobile lê da VPS pela rede aberta, sempre);
  - **correlação por adversário global passivo / análise de timing** é limite do próprio
    Tor, não validável numa POC;
  - **padrões temporais de publicação** (timestamps em manifestos, horários de `seq`) podem
    revelar fuso/rotina — registrado, jamais prometido.
- **Critério de sucesso do modo** = as quatro asserções de não-vazamento (§D5), **não**
  "conectou via Tor". Uma célula que _funcione mas vaze_ em qualquer camada é ❌.

## 1. Questões Q1–Q10 (fixadas a priori) e status corrente

| # | Questão | Status | Evidência |
|---|---|---|---|
| **Q1** ⚑ | Publicador anônimo fecha o E2E (push → R clearnet → fetch mobile) com zero vazamento auditado? | **sim, não-vazamento AUDITADO E LIMPO** | E2E fechado no Moto g(30) em dados móveis [executado]. Auditoria com R em VPS SEPARADA: **0 pacotes ao IP de R** durante o push anônimo (P só via Tor), 0 DNS do onion, e sem fallback de dial direto. Co-localização resolvida (§ E4-T) |
| **Q2** ⚑ | Descoberta através do Tor funciona (P conhece só B, descobre R)? | **sim, nos dois** | P (só conhece B via onion) descobre R por dentro do túnel: Trama ~3,7 s, libp2p ~22 s [executado] |
| Q3 | `push` cabe no seam sem branch de app e sem endereço de origem no wire? | **sim** | `PushRequest` sem campo de origem; seam neutro; TCK verde [executado] |
| Q4 | Quantos pontos de vazamento novos o modo anônimo adiciona ao `:api`? | **1 (meta batida)** | só `Capability.ANONYMOUS_DIAL`; config de anonimato é fábrica, não app [executado] |
| Q5 | Não-vazamento passa nos dois backends, ou algum swarm/DNS/mDNS vaza? | **contido nos dois** (funcional); pcap = E4 | Trama SOCKS5h; libp2p transporte SOCKS-only ⇒ dial direto impossível (contenção estrutural) [executado] |
| Q6 | Custo de latência honesto do modo? | **~33× mais lento** (medição simétrica desktop→VPS pela internet: clearnet ~2,1 MB/s → Tor ~64 KiB/s, mediana); Tor variável 33-115 KiB/s; setup frio Trama 4,7 s × libp2p 15,8 s [dado-só] | push desktop→VPS real, § "Velocidade de transferência" |
| Q7 | O modo anônimo favorece a Trama? | **sim, no ESFORÇO** (ambos funcionam) | Trama: SOCKS trivial no JDK, 0 atrito. libp2p: Transport custom + serde_bytes + fix de corrida de dial [executado] |
| Q8 | Custo de esforço por adapter cabe no veto (5 dias)? | **sim nos dois**: Trama « 1 dia, libp2p « 5 dias | o veto NÃO estourou no libp2p (célula viável) [executado] |
| Q9 | Robustez de circuito (morte no meio do push)? | **sim (com retry de app-level)** | `CircuitRig` mata circuitos via control port no meio do push; retry re-disca em circuito novo; capítulo verificado — nos dois backends [executado] |
| **Q10** ★ | Veredito: viável e abaixo do seam / viável mas vaza / inviável num backend — gatilho invertido? | **viável e abaixo do seam (funcional); GATILHO INVERTIDO confirmado** | §5 |

## 2. Limiares refixados para Tor (D7 — fixados a priori) [só-design]

| Métrica | Clearnet (poc-04) | Limiar Tor (poc-05) | Medido até agora |
|---|---|---|---|
| Handshake Noise (frio, circuito novo) | < 1 s | **< 10 s** | (embutido no push abaixo) |
| Requisição quente (circuito estabelecido) | < 500 ms | **< 2 s** | pendente |
| Push do capítulo (768 KiB) | ~3 s | **< 60 s** | Trama **~14-17 s**, libp2p **~12-17 s** ✅ [dado-só] |
| Lookup frio via Tor (C2) | ≤ 3 RTTs | **< 10 s** | Trama **~3,7 s** ✅ / libp2p **~22 s** ⚠ [dado-só] |
| Vazamento (pcap) | — | **0 pacotes** fora do Tor (binário) | pendente (sudo, E4) |
| Retoma pós-morte de circuito | — | recupera sem intervenção | ✅ retry re-disca, 2 tentativas, ~28 s [dado-só] |
| Mudança no app leitor | 0 linhas | **0 linhas** | pendente (E4) |
| Vazamento de seam novo | 3 pts (poc-04) | **≤ 1 novo**, documentado | **1** (`ANONYMOUS_DIAL`) ✅ [executado] |
| Veto de esforço | 5 dias/adapter | **5 dias/adapter** | Trama « 1 dia ✅ |

## 3. O que foi executado até aqui

### E0 — seam estendido + push + TCK (o instrumento) [executado]

- Módulos `poc05/api`, `poc05/trama`, `poc05/node` criados a partir dos do poc-04 (D1:
  reusar o seam, estender — não redesenhar), registrados no `settings.gradle.kts`.
- Seam de client (`P2pBackend`) ganhou **`push(target, obra, manifestBlock, blocks)`** — o
  publicador não-discável **empurra** (inverte o pull do poc-02/04). O frame de push
  (`PushRequest`) **não carrega endereço de origem** (D1) — só obra + manifesto + blocos.
- Seam de full node (`FullNode`) ganhou **`acceptPushes(publisherKeyHex)`**: o replicador
  declara a editora cujo conteúdo topa hospedar. A decisão "aceita conhecido, rejeita chave
  errada" é neutra e reusada pelos dois backends (`PushPolicy`, no `:api`) — valida a
  assinatura do manifesto **ANTES de gravar**.
- Config de anonimato (`AnonymityConfig`) é **parâmetro de fábrica de backend** (D2), nunca
  código de app; capability consultável `Capability.ANONYMOUS_DIAL` — **o único ponto de
  vazamento novo** sobre os 3 do poc-04 (meta do E5 batida).
- TCK do push escrito **antes** do adapter (task 2.3) e verde no backend Trama em clearnet:
  `push autenticado e gravado e depois servido` e `push de chave errada rejeitado antes de
  gravar`. Testes de unidade do `:api`: `PushPolicy` e `AnonymityConfig`.

### E1 — modo anônimo no backend Trama, sobre Tor REAL [executado]

- Dial da Trama passa por `java.net.Proxy(SOCKS, 127.0.0.1:9050)` com endereço
  **não-resolvido** (`InetSocketAddress.createUnresolved` → **SOCKS5h**, resolução remota):
  nenhuma resolução de nome sai da máquina (D4). Um `.onion` **não resolve localmente por
  construção**, reforçando a garantia.
- Rig Tor local self-contained (tasks 1.3): daemon `tor` 0.4.9.11 com `SocksPort 9050` +
  **onion service v3** (`torrc` em `poc05/rig/tor-publisher/`) mapeando o circuito → Trama
  R em `127.0.0.1:4100`. Circuito real: `Bootstrapped 100%`, onion
  `mg332…o5gxn7ad.onion`.
- **`AnonRig` (executado):** publicador anônimo (`ANONYMOUS_DIAL` presente) empurra a obra
  de 768 KiB **pelo onion** (6 hops); R grava; leitor clearnet baixa e **verifica 786432
  bytes íntegros**. Handshake Noise autentica a identidade Ed25519 de R **através do
  circuito**. Push de chave errada **rejeitado pelo túnel**, R não grava. Latência de push:
  **~14,7 s** (< 60 s). Nas conexões, R vê `noise:/127.0.0.1:…` — o onion entrega **sem IP
  de origem discável** (critério onion do D5.3).

### E2 — modo anônimo no backend rust-libp2p, sobre Tor REAL [executado]

O candidato a estourar o veto (D6) — **coube**. O facade rust do poc-04 foi estendido com:

- **`SocksTransport` custom** (`poc05/rust-facade/src/socks.rs`): disca TCP por SOCKS5 com
  resolução remota (SOCKS5h, `tokio-socks`), **sem listen** (publicador só saída). Como é o
  ÚNICO transporte do swarm anônimo, **nenhum dial direto é possível** — a contenção de
  identify/mDNS/DNS/relay é **estrutural**, não configurada. **QUIC desligado** (o swarm
  anônimo não chama `.with_quic()`).
- **`AnonBehaviour` mínimo** (kad client + blocks + push) — sem identify/dcutr/relay/mDNS.
- **`push` como request-response** (`/opentoons/push/1.0.0`, CBOR+`serde_bytes`); o receptor
  chama `accept_push` de volta ao Kotlin, que roda a MESMA `PushPolicy` neutra e grava.

**Executado (`AnonRig --backend=libp2p` sobre o onion real):** push de 768 KiB pelo circuito
em **~12-17 s** (< 60 s); R (libp2p) recebe, valida a editora, grava; leitor clearnet baixa e
**verifica 786432 bytes íntegros**; push de chave errada rejeitado pela política **através do
túnel** (`accepted=false`). O MESMO driver neutro roda os dois backends (zero branch).

**Atritos do E2 (dado do relatório — Q7/Q8):** três, todos ausentes na Trama —
(1) o Transport SOCKS não vem pronto (escrito à mão); (2) `Vec<u8>` em CBOR vira
array-de-inteiros e estoura o limite de 1 MiB do request-response → precisou `serde_bytes`;
(3) corrida entre o dial do kad e o dial implícito do request-response sobre circuito lento →
`DialFailure` prematuro, resolvido adiando o `send_request` até `ConnectionEstablished`.
Esforço total « 5 dias — **o veto não estourou**; a célula libp2p é **viável**.

### E3 — descoberta do replicador ATRAVÉS do Tor (C2) [executado]

Topologia de **dois onions** (`DiscoveryRig`): B (bootstrap, onion :4200) e R (replicador,
onion :4100, anuncia a obra que hospedará). O publicador anônimo P conhece **só B**; descobre
R — endereço nunca informado — por dentro do túnel, e empurra ao R descoberto.

- **Trama (PEX/RESOLVE, 1 circuito):** P descobre R em **~3,7 s** (< 10 s ✅); push ao R
  descoberto gravado e verificado (786432 bytes).
- **rust-libp2p (walk de Kademlia, multi-circuito):** P descobre R em **~22 s** — **EXCEDE o
  limiar de 10 s** (dado honesto). Cada hop do walk de Kademlia abre um **novo circuito Tor**;
  a latência de circuito multiplica pela profundidade do walk. Ciclo completo verde, mas lento.

**A divergência que o design previu, medida (Q7):** a descoberta anônima é **~6× mais lenta**
no libp2p (22 s) que na Trama (3,7 s). O gossip de 1 circuito da Trama é estruturalmente mais
barato sobre Tor que o walk multi-dial do Kademlia — **forte evidência de gatilho invertido**
(um requisito de anonimato é argumento CONTRA migrar da Trama ao libp2p).

### E4 — matriz E2E real no Moto g(30) (C1) [executado]

O port-forward `4000-4999` foi apontado para esta máquina (`.13`, público `177.203.17.5`).
Topologia real (`E4Rig`): B e R **públicos** em `177.203.17.5`; P **anônimo** empurra a obra a
R **pelo Tor** (onion); R anuncia-se público; o leitor **M = Moto g(30) em dados móveis**
(`172.20.10.x`, rede separada — rota confirmada `via 172.20.10.1 dev wlan0`) baixa de R pela
**clearnet**. Alcançabilidade externa confirmada com o próprio device (dial chegou em R).

**C1 VERDE nos dois backends, no hardware real** — o MESMO app (só a build variant muda):

| Backend | dial | resolve | total | resultado |
|---|---|---|---|---|
| Trama | 356 ms | 662 ms | 2411 ms | ✅ 786432 B verificados |
| rust-libp2p | 115 ms | 1011 ms | 2195 ms | ✅ 786432 B verificados |

Prova o essencial da tese: o capítulo que o publicador anônimo empurrou por dentro do Tor foi
baixado por um device físico em rede móvel separada e **verificado** (assinatura Ed25519 +
hashes), com a anonimidade do publicador **invisível ao leitor** (mobile lê clearnet, 0 Tor,
0 branch por backend no app — grep confirma). A `.so` Android release do libp2p: **11,7 MB/arm64**
(Trama não tem nativo — o dado de regressão de peso da Q4).

**C2 (descoberta via Tor) — célula única VERDE nos dois backends no device.** Implementado o
`advertise` (dual-homed) no seam: R anuncia onion (para o P anônimo) + IP público (para o
device). P descobre R via B pelo túnel e empurra; o Moto g(30) descobre R via B e verifica
786432 B. NUANCE honesta: a Trama propaga o dual-homed limpo (P casa o onion); no libp2p o
onion `/dns` NÃO propagou no provider record do Kademlia (P usou `/ip4` sobre Tor) — mais um
ponto a favor da Trama.

**Rejeições no device (real):** com R servindo blocos adulterados (`--tamper`), o Moto g(30)
**recusa** o capítulo com `BlockHashMismatch` — a verificação do leitor pega a adulteração no
hardware. Chave errada é rejeitada no RECEPTOR (R recusa o push de chave errada; o conteúdo
nunca chega ao leitor).

### E4-T — auditoria de não-vazamento (D5) [executado]

Método adversarial em duas frentes (pcap privilegiado + captura por processo):

1. **pcap (`sudo tcpdump` em en0, durante o push anônimo):**
   - **0 de 152** pacotes DNS mencionam o onion → **sem DNS leak** (SOCKS5h confirmado: o
     `.onion` nunca foi a um resolver).
   - **circuito Tor confirmado no wire**: relays do consenso (`64.65.0.66`, `85.195.244.251`,
     conferidos contra o Onionoo) carregaram o tráfego do push.
   - **0 dial direto de P**: o único tráfego à porta de R (`:4100`) é hairpin **B↔R**
     (`177.203.17.5`→`192.168.1.13:4100`) — os servidores, **públicos por design**, fazendo
     mesh; NÃO o publicador.
2. **captura por processo (`lsof` do PID de P isolado, sem sudo):** durante o push, a única
   conexão TCP de P é `127.0.0.1:<porta>→127.0.0.1:9050` (o daemon Tor); **0 sockets de
   escuta**. P não faz nenhum dial direto nem resolução local — é SOCKS-only por construção.
3. **caminho onion (camada 3):** R registra a conexão como `noise:/127.0.0.1:…` — **sem IP
   de origem discável** (o onion entrega sem exit).
4. **wire do push (camada 4):** `PushRequest` não tem campo de endereço de origem (D1).

**Auditoria LIMPA com R em máquina separada (VPS real) — a ressalva de co-localização
RESOLVIDA.** Rodou-se R+B numa **VPS Ubuntu separada** (`143.95.220.165`) e P no desktop; a
captura na interface do desktop durante o push ANÔNIMO (Tor) mostra:
- **0 pacotes** para o IP de serviço de R (`143.95.220.165:4100`) — P alcança R
  **exclusivamente pelo circuito Tor**, nenhuma conexão direta.
- **0 DNS** mencionam o onion (`.onion` nunca vai a um resolver).
- Como controle, o push CLEARNET (sem Tor) do mesmo P **conecta direto** a `143.95.220.165:4100`
  — o pcap distingue os dois modos com nitidez: anônimo → 0 direto; clearnet → direto.
- Propriedade forte observada: quando o circuito onion falhou (flakiness do Tor), P **falhou**
  em vez de vazar — **não há fallback de dial direto** no modo anônimo.

A captura POR PROCESSO (P só fala com `127.0.0.1:9050`) continua válida como método complementar.
Com R fora da máquina de P, o critério binário "0 pacotes para R fora do Tor" é agora **literal
e limpo** — a ameaça à validade da co-localização não se aplica mais.

### Velocidade de transferência: clearnet × Tor [dado-só]

Transferência de 768 KiB (3 blocos). **Medição PRINCIPAL — node↔node pela INTERNET REAL,
SIMÉTRICA:** publicador P no **desktop** (rede residencial, `177.203.17.5`) empurrando para R
numa **VPS separada** (`143.95.220.165`, Ubuntu 22.04, IP público direto) — mesma operação,
mesma direção (upload), os dois pela internet. Trama, push repetido 6×.

Três medições **node↔node pela internet real** (P/leitor ↔ VPS `143.95.220.165`):

| Caminho | Throughput (mediana) | Amostras | Natureza |
|---|---|---|---|
| **Clearnet push** (desktop → VPS, upload) | **~2,1 MB/s** | 359-394 ms, estável | publicador → VPS, internet real |
| **Clearnet download** (device → VPS) | **~721 KiB/s** | 793-1171 ms | leitor (Moto g30, WiFi) ← VPS, internet real |
| **Tor push** (desktop → VPS onion, upload) | **~64 KiB/s** | 6,7-23 s, 33-115 KiB/s | anônimo → onion real da VPS |

**O custo honesto do anonimato: ~11× vs. download real do leitor** (721 → 64 KiB/s) ou **~33×
vs. upload clearnet do publicador** (2,1 MB/s → 64 KiB/s). O Tor tem throughput baixo (~64
KiB/s) e **alta variabilidade** (33-115 KiB/s — o jitter real da rede Tor, que a medição
co-localizada escondia). O clearnet escala com o enlace (2 MB/s upload, ~720 KiB/s no device);
o Tor é limitado pelo circuito, por construção.

**Referências secundárias** (co-localizado, para contexto): push Tor co-localizado **~170
KiB/s** (onion em hairpin na mesma máquina — otimista, sem o custo do circuito remoto completo);
loopback/hairpin do `SpeedRig` 17-107 MB/s (memória, não-internet). Todas as medições
principais acima são pela internet real, com a VPS separada.

**Setup de circuito (frio):** Trama ~4,7 s × libp2p ~15,8 s — a negociação libp2p sobre Tor
(multistream + Noise + Yamux, round-trips extras) é ~3× mais cara que o handshake Noise de um
frame da Trama. Mais um ponto de custo do libp2p sobre Tor.

**Julgamento de UX:** o push de 768 KiB varia de ~6 s a ~23 s sobre Tor (mediana ~12 s) — ainda
dentro do limiar D7 (< 60 s). Publicar é **assíncrono e raro** (ADR-0001), tolera dezenas de
segundos; mas a variabilidade do Tor (até 23 s) reforça que o modo anônimo é para publicação de
fundo, não interativa. O throughput baixo (~64 KiB/s) só seria problema para obras muito grandes
num único push; o modelo é por capítulo.

### Rig de auditoria (task 1.4) [executado]

- `poc05/rig/audit-exits.sh` — cruza IPs contra o consenso Tor (Onionoo). **Validado nos
  dois sentidos:** identifica um exit real (`204.8.96.141` ✅) e acusa um IP não-Tor
  (`8.8.8.8` ❌).
- `poc05/rig/audit-listen.sh` — 0 sockets de escuta não-loopback em P (o `TramaClient` não
  liga `ServerSocket` algum: garantia estrutural — client é só saída, ADR-0005).
- `poc05/rig/audit-pcap.sh` — captura binária "0 pacotes fora do Tor". Precisa de `sudo`
  (a execução final da camada 1 do D5 fica para o rig com privilégio).

## 4. Limites desta rodada / ameaças à validade

- **Rig self-contained numa máquina só:** P, R e o daemon Tor coabitam em `192.168.1.13`.
  Isso **prova o mecanismo** (SOCKS5h, Noise através do circuito, push, aceitação) mas
  **não** substitui a matriz E2E multi-máquina do E4 (R/B com IP público em `192.168.1.15`,
  leitor no Moto g(30) em dados móveis) nem a camada 1 do D5 (pcap binário com `sudo`).
- **E2 concluído** (rust-libp2p sobre Tor viável, funcional sobre onion real). Falta só a
  camada 1 do D5 (pcap binário com `sudo`) para fechar o não-vazamento por auditoria — a
  contenção do swarm libp2p é estrutural (SOCKS-only), mas o pcap é a prova dura.
- **E3/E4/E5** dependem da infra completa do rig (VPS pública, device físico, captura
  privilegiada). Serão preenchidos quando o rig estiver montado; nada aqui é declarado sem
  execução.

## 5. Veredito (Q10) [síntese sobre E0–E3 executados]

**Modo anônimo: VIÁVEL e ABAIXO DO SEAM, nos dois backends — funcionalmente provado sobre
Tor real.** O publicador anônimo fecha o ciclo do produto (push → R → fetch verificado) por
dentro de um circuito Tor onion, nos dois cenários (C1 IP conhecido, C2 descoberta via Tor),
com **1 único ponto de vazamento novo** no seam (`ANONYMOUS_DIAL`), **zero branch de app**, e o
mesmo driver neutro rodando os dois backends. Ressalva honesta: o não-vazamento está provado
**funcionalmente e por construção** (SOCKS5h sem DNS local; onion sem IP de origem discável;
transporte SOCKS-only ⇒ dial direto impossível no libp2p), mas a **camada dura do D5 — o pcap
binário "0 pacotes fora do Tor"** — exige o rig privilegiado (`sudo`, E4). Até lá o veredito é
"viável e abaixo do seam, **não-vazamento auditado pendente**", não "provado à prova de pcap".

**GATILHO INVERTIDO: confirmado.** O modo anônimo favorece a Trama de forma consistente e
medida — é argumento **contra** migrar da Trama ao rust-libp2p, o oposto dos gatilhos da Q10 do
poc-04:

| Eixo | Trama | rust-libp2p |
|---|---|---|
| Dial anônimo | `Proxy(SOCKS)` do JDK, **0 atrito** | Transport SOCKS custom (não vem pronto) + `serde_bytes` + fix de corrida de dial |
| Descoberta via Tor | PEX/RESOLVE, 1 circuito — **~3,7 s** | walk de Kademlia, N circuitos — **~22 s (6×)** |
| Superfície a conter | 100% do projeto (nada disca sozinho) | swarm contido; contenção estrutural (SOCKS-only) |
| Esforço do adapter | « 1 dia | ~1-2 dias (« veto de 5) |

Ambos **funcionam**; o libp2p **cabe no veto** (não estourou). Mas a assimetria de esforço e,
sobretudo, a **descoberta 6× mais lenta sobre Tor** (custo estrutural do multi-circuito do
Kademlia) tornam o anonimato um **argumento de peso a favor de commitar na Trama** para o
Marco 4 — exatamente a hipótese a priori, agora com dado.

## 6. Ameaças à validade (registradas honestamente)

- **Rig co-localizado — RESOLVIDO ao final.** A maioria das células rodou com P+R+B+tor na
  mesma máquina (mitigado pela captura por processo). Mas as medições finais de **velocidade**
  e **pcap** foram refeitas com **R+B numa VPS Ubuntu separada** (`143.95.220.165`) e P no
  desktop — dando o custo real (~33×, § velocidade) e o não-vazamento LIMPO (0 pacotes ao IP de
  R durante o push anônimo, § E4-T). A co-localização deixou de ser ameaça nas conclusões-chave.
- **Exit policy do Tor (achado D3):** o push via EXIT à porta não-padrão 4100 deu timeout —
  as exit policies bloqueiam portas incomuns. **Não é falha**: é dado a favor do caminho
  ONION (dual-homed), como o design previu. O caminho onion foi usado em todas as células
  bem-sucedidas.
- **C2 numa célula única exige anúncio dual-homed:** R é dual-homed (onion para P, IP público
  para o device), mas o anúncio atual carrega UM endereço. As duas pernas do C2 estão provadas
  isoladas (descoberta via Tor + fetch no device); combiná-las num run só precisa de R anunciar
  ambos os endereços — item de design do Marco 4.
- **Poucos nós / timing:** com B+R+P locais é trivial correlacionar por horário — limite do
  cenário e do próprio Tor, não critério; registrado no modelo de ameaças, jamais prometido.
- **Peso libp2p em debug:** a `.so` release/arm64 (11,7 MB) é o número honesto; o dylib de host
  debug (32 MB) não é métrica de peso.

## 7. Recomendações para o Marco 4

- **ADR formalizando o Tor** como alternativa de **alcançabilidade E privacidade** (hoje a dupla
  função está implícita e espalhada entre ADR-0001 e ADR-0006).
- **Superfície `push`** no módulo de rede: o publicador não-discável empurra; o receptor valida
  a editora (`PushPolicy`) antes de gravar. Provado barato e neutro nos dois backends.
- **Seam aceitando endereços NÃO-IP** (strings opacas + filtro "discável por este backend"):
  o que destrava o anúncio dual-homed (onion + IP público) e o C2 numa célula única. Custo zero
  agora, caro de retrofitar.
- **Commit na Trama para o modo anônimo:** o gatilho invertido é forte (descoberta 6× mais
  lenta no libp2p sobre Tor, SOCKS trivial na JVM). Se um requisito de anonimato entrar no
  Marco 4, ele **contrapesa** os gatilhos de migração ao libp2p da Q10 do poc-04.

## 8. Estado dos experimentos

E0 ✅ · E1 ✅ · E2 ✅ (libp2p sobre Tor viável, veto não estourou) · E3 ✅ (divergência 6×
medida) · E4 C1 ✅ (device real, dois backends) · E4 C2 ✅ (célula única, dual-homed, device,
dois backends) · E4 rejeições ✅ (device recusa adulterado) · E4-T ✅ (não-vazamento auditado) ·
E5 robustez ✅ (retoma pós-morte de circuito) · E5 inventário/LoC/peso ✅ · relatório ✅.
**31/31 tarefas fechadas.**

**Esforço real (Q8):** adapter Trama « 1 dia (SOCKS é `java.net.Proxy`, 0 atrito); adapter
libp2p ~1-2 dias (Transport SOCKS custom + `serde_bytes` + fix de corrida de dial) — ambos
**muito abaixo do veto de 5 dias**. O grosso do trabalho novo foi o *transporte anônimo* e o
*push*, não os motores (reusados do poc-02/04). Referências cruzadas: roadmap (extensão
poc-05), ADR-0001 (Tor como via do publicador não-discável) e ADR-0006 (Tor+`push` alternativo
ao furo de NAT).
