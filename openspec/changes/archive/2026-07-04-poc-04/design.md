# Design: poc-04 — abstração de P2P com backend trocável

## Context

O poc-02 ([relatório](../../../docs/poc02-report.md)) recomendou a stack própria e venceu em todas as métricas (APK 0,96 MB, 0,13 MB/sessão, 0 bug de terceiros); o poc-03 ([relatório](../../../docs/poc03-report.md)) provou que o rust-libp2p é shippável (8–11 MB/ABI) mas deliberadamente não decidiu stack. O resultado agregado: **a própria ganha hoje, o libp2p ganha depois** — hole-punch DCUtR/relay v2, transportes plugáveis e Kademlia de produção são "avaliar no Marco 4" (ADR-0006, gatilho de migração gossip→DHT em ≈ 5.000 registros do poc-02), e custariam semanas para construir na própria. O poc-04 testa a terceira via: **não acoplar** — uma fronteira de módulos onde o backend P2P é trocável em build-time, transformando a migração prevista pelo poc-02 num flag de build e a estratégia `própria → rust-libp2p` numa opção condicional de-riscada.

Fatos das POCs anteriores que moldam o design:

- **O seam de client já está provado** (poc-03): `dial`/`resolve`/`getBlocks` cruzam a FFI, `verify` (Ed25519 + hash) fica em Kotlin fora do seam (D7 de lá), 40 chamadas concorrentes sem bug FFI. Não é isso que a poc-04 precisa provar.
- **O seam de full-node nunca foi tentado**: o publicador do poc-03 era um binário Rust avulso, fora de qualquer interface; o nó do poc-02 (`FullNode.kt`) é acoplado à própria stack. `announce`/`serve`/`republish` é onde gossip (epidemia, refresh O(n)) e Kademlia (provide K-closest, republish) mais divergem — **é o teste de verdade da abstração**.
- **Os dois motores funcionam** (E2E do Marco 0 fechado 3×, mesmo rig): o risco da poc-04 é a *fronteira*, não os motores.
- **Incompatibilidade de fio é estrutural**: um nó Trama e um nó libp2p são mutuamente surdos (Noise+RPC próprio+gossip × Noise+yamux+Kademlia+req-resp). Trocar backend no app não migra a *rede* — a ponte é o full node dual-stack (D7 abaixo).

```
        :api  (commonMain, Kotlin puro)
        interface P2pBackend (client) + FullNode (servidor)
        tipos neutros + verify (Ed25519+hash) + TCK
             ▲                    ▲
     :trama (poc-02 real,   :libp2p (facade rust do poc-03
      Noise XX+RPC+gossip,   + LADO SERVIDOR novo via FFI,
      client E full node)    client E full node)
             ▲                    ▲
             └─────────┬──────────┘
               app/nó ──▶ :api + EXATAMENTE UM backend por build
```

## Goals / Non-Goals

**Goals:**

- Provar (ou refutar) que **um seam único hospeda os dois backends, client E full node**, com implementação real dos dois lados, sem falseamento — nem simulação in-process, nem servidor fora da interface.
- Responder as questões Q1–Q12 fixadas a priori (abaixo), culminando num **veredito de estratégia**: `própria`, `rust-libp2p` ou `própria → rust-libp2p condicional a gatilho` — diferente do poc-03, esta POC **termina com recomendação**.
- Medir o **custo da abstração**: pontos de vazamento (métrica-mãe), regressão de peso por backend, LoC de cola, esforço por adapter (com veto).
- Mapear **features no tempo** (curto/médio/longo): o que cai abaixo do seam (swap = 0 diff no app) e o que vaza.
- Provar a **ponte de migração**: full node dual-stack servindo o mesmo blockstore aos dois tipos de cliente — migração sem flag day.
- Fechar a **matriz E2E 4×2** (malha multi-nó; transferência real ao mobile; descoberta transitiva; internet real por rede separada) com o mesmo app, zero branch por backend.

**Non-Goals:**

- Re-provar que cada stack funciona isolada (baselines poc-02/03) ou re-medir bateria (poc-03 registrou a limitação do rig USB; referenciar poc-02).
- Interop Amino/IPFS — não-objetivo do produto (rede própria, ADR-0001); o vazamento de ecossistema é irrelevante por construção.
- Backend go-libp2p — vetado pelo peso no poc-03 (33–35 MB/ABI > teto de 20 MB).
- Rodar os dois backends **no mesmo APK mobile** — a comparação é entre builds, nunca em runtime no dispositivo (D1).
- Throughput a granel (lacuna assumida das POCs anteriores), hardening, formato final de wire — código descartável com rigor mínimo para conclusões críveis.
- Decidir o *nome de produto* da camada de rede — "Trama" é o nome de trabalho do backend próprio nesta POC.

## Questões a responder (Q1–Q12, fixadas a priori)

O relatório final responde uma a uma; são o contrato de conclusão da POC.

| # | Questão | Respondida por |
|---|---|---|
| **Q1** ⚑ | O seam de **full-node** unifica gossip e Kademlia atrás da mesma interface, ou `announce`/`republish`/`serve` forçam métodos específicos por backend? | E2 |
| Q2 | Quantos **pontos de vazamento** o `:api` tem no total? Todos são capability-flag documentada, ou algum é branch de app? | E5 |
| Q3 | Os **tipos neutros** seguram? (`ContentId` sha256 × CID; `BootstrapAddr` ip:port × multiaddr; identidade Ed25519 × PeerId) | E0/E2 |
| Q4 | A fronteira é **~grátis em peso**? (app+trama ~0,96 MB; app+libp2p ~8–11 MB/ABI; regressão ≤ +5%; um backend por build) | E5 |
| Q5 | A **curto prazo** (Marco 2), a própria cobre 100% pela interface? | E1/E2/E4 |
| Q6 | Que features de **médio/longo prazo** caem abaixo do seam e quais vazam? (hole-punch; gossip→DHT; browser/WebRTC) | E3 |
| Q7 | O que o libp2p entrega que a própria teria que **construir do zero**, e qual o tamanho desse "do zero"? | E3 + análise |
| Q8 | Ganhar uma feature só-libp2p custa **0 linha de app**? | E3 |
| Q9 | Migração **sem flag day** é factível? (dual-stack serve os dois clientes com bytes idênticos verificados) | E5 |
| Q10 | Qual o **gatilho concreto e observável** para percorrer `própria → libp2p`? | veredito |
| **Q11** ★ | Estratégia: `própria` / `libp2p` / `própria → libp2p condicional`? | síntese |
| **Q12** ★ | Manter o adapter libp2p vivo (FFI + Rust no CI + disciplina de não-vazar) **vale a opcionalidade**, ou "commit na própria e assumir o acoplamento" é mais honesto? | E5 + julgamento |

Encadeamento: Q1 "não" → fim da tese (commit num backend). Q1 "sim" → Q6/Q7/Q9 dão o valor da opção → Q11/Q12 fecham o veredito.

## Decisions

### D1 — Fronteira em BUILD-TIME (módulos + build variant); o mobile nunca carrega os dois backends

```
runtime/DI:  os 2 no APK → paga +8–11 MB/ABI SEMPRE → mata a vitória do poc-02 → ✗
build-time:  1 backend por build variant → app+trama ~0,96 MB; app+libp2p ~8–11 MB → ✓
```

A comparação lado a lado é **entre builds** (dois APKs, mesmo app, variants `trama`/`libp2p`), nunca em runtime no dispositivo. Exceção deliberada: o **full node de host** (desktop/VPS) pode carregar os dois — é a ponte dual-stack (D7), onde peso é irrelevante. Requisito estrutural: **0 branch por backend no código do app** — a escolha vive inteiramente no grafo de dependências do build.

### D2 — O seam são DUAS interfaces (`P2pBackend` client; `FullNode` servidor) com tipos 100% neutros; `verify` fora do seam

- **`P2pBackend` (client, mobile):** `dial(bootstrap)` / `resolve(obraId): List<Provider>` / `getBlocks(provider, ids): List<Block>` / `close()` — a superfície que o poc-03 já provou atravessar FFI.
- **`FullNode` (servidor, desktop/CLI):** `start(listen, bootstrap)` / `publish(obra, blocks)` / `announce(obraId)` / `serve(blockstore)` / `stop()` — com expiry/republish **internos ao backend** (a semântica de re-anúncio difere demais para vazar: epidemia O(n) × K-closest; o app só declara *o que* anuncia, nunca *como*).
- **Tipos neutros no `:api`:** `ObraId` (string), `ContentId` (sha256 — o denominador comum; o backend libp2p converte para CID internamente), `Provider` (identidade + endereços opacos), `BootstrapAddr` (host:port + chave pública; o adapter libp2p monta multiaddr/PeerId internamente), `Block`, `Manifest`. Nenhum tipo de nenhum backend aparece na interface.
- **`verify` (Ed25519 + hash por bloco) vive no `:api` em Kotlin**, fora do seam — D7 do poc-03, mesmo código para qualquer backend, verificação comparável entre POCs.
- Capacidades divergentes (ex.: hole-punch) entram como **capability-flag** consultável (`capabilities: Set<Capability>`), nunca como método específico — e cada flag conta no inventário de vazamento (D4).

### D3 — Implementação real dos dois lados, sem falseamento (a restrição dura da POC)

- **`:trama`** = a stack do poc-02 (Noise XX + RPC de frames + membership/gossip) **refatorada para trás da interface** — client e full node. Refatoração honesta do `FullNode.kt` do poc-02, não wrapper que delega para código fora do seam.
- **`:libp2p`** = o facade rust do poc-03 **copiado para `poc04/rust-facade` e estendido com o lado servidor** via FFI: `listen` (TCP+QUIC), Kademlia `Mode::Server`, `start_providing`, servir blocos por Request-Response — dirigido pela interface Kotlin `FullNode`. Exige **build de host** (dylib macOS arm64, mesmo `.udl`) para o full node em desktop JVM, além dos `.so` Android. É o maior trabalho novo da POC; **veto de esforço: ≤ 5 dias úteis por adapter** (estourar = resultado válido: "o seam custa demais para o backend X").
- **Proibições explícitas:** sem simulação in-process nos experimentos que contam (a simulação do E3 do poc-02 não se repete — aqui tudo é socket/processo real); sem publicador fora da interface (o erro honesto do poc-03 não se repete); `adb reverse` só para smoke test de desenvolvimento, nunca nas células da matriz E2E.

### D4 — TCK antes dos adapters; inventário de vazamento como métrica-mãe

A suíte de conformidade (TCK) do `:api` é escrita **antes** de qualquer adapter (E0) e roda **idêntica** nos dois — mesmos vetores, mesmos cenários (resolve, download, verificação, rejeição de adulterado, expiry/republish). Ela é o instrumento que torna "os dois backends se comportam igual" um fato mecânico, não uma opinião.

**Inventário de vazamento** = a lista exaustiva de pontos onde conhecimento específico de backend escapa do `:api` para app/config (capability-flags, parâmetros de config específicos, semânticas documentadas-mas-divergentes). É a métrica-mãe da qualidade da abstração: **≤ 3 pontos, todos capability-flag documentada** = seam saudável; acima disso, ou qualquer `if (backend is …)` no app, a abstração falhou (Q2).

### D5 — Limiares fixados a priori

Definidos ANTES de qualquer medição; ajustes exigem justificativa registrada no relatório.

| Métrica | Cenário | Limiar |
|---|---|---|
| Matriz E2E | S1–S4 × 2 backends, mesmo app | **8/8 células verdes** |
| Ramificação por backend | código do app/nó | **0** branches (`if backend is …`) |
| Paridade de TCK | mesma suíte nos dois backends | **100% verde nos dois** |
| Pontos de vazamento do `:api` | inventário completo (D4) | **≤ 3**, todos capability-flag documentada |
| Regressão de peso (trama) | APK release+R8 vs 0,96 MB (poc-02) | **≤ +5%** |
| Regressão de peso (libp2p) | APK por ABI vs 8–11 MB/ABI (poc-03) | **≤ +5%** |
| Espessura do adapter | LoC de cola por backend (fora do motor) | **≤ ~150 LoC** cada |
| Feature abaixo do seam | ganhar hole-punch trocando backend | **0 linha** de app alterada |
| Ponte dual-stack | full node 2-stack → cliente-trama E cliente-libp2p | **bytes idênticos + assinatura OK** nos dois |
| Republish pós-morte | S1: matar publicador, esperar expiry, reviver | provider some e volta, **nos dois** backends |
| Descoberta transitiva | S3/S4: app conhece só o bootstrap | publicador **nunca informado** confirma conexão do app |
| Veto de esforço | cada adapter (trama; libp2p) até a matriz E2E | **≤ 5 dias úteis** cada |
| Latências (sanidade, não é o foco) | handshake / reconexão / lookup frio, rig real | < 1 s / < 500 ms / ≤ 3 RTTs (mesmos do poc-02/03) |

### D6 — Matriz E2E 4×2 como critério duro de realidade

O julgamento final do seam não é o TCK (unit/integration) — é a matriz, com sockets, processos, dispositivo físico e internet reais, **o mesmo app nas 8 células**:

| Cenário | O que prova | Setup |
|---|---|---|
| **S1** malha multi-nó | membership/DHT reais convergem; expiry/republish sob morte de nó | 4 full nodes em **processos separados** (bootstrap A + publicador P + R1 + R2), portas distintas; matar P e verificar expiry→republish |
| **S2** transferência real | full node serve capítulo real ao mobile pela interface | Moto g(30); manifesto Ed25519 + blocos; verificação + rejeição de adulterado |
| **S3** descoberta transitiva | descoberta fria: P nunca informado é encontrado via A | app conhece **só** A + obraId; P confirma conexão vinda do app |
| **S4** internet real | o critério do Marco 0, por rede separada | full nodes no IP público (port forwarding 4000-4999, check-host.net); Moto g(30) em dados móveis/hotspot de outra operadora |

Encadeamento com E2: se o seam "segurar" no TCK mas alguma célula exigir branch por backend no app, **Q1 falha na prática** — é exatamente o vazamento-sob-realidade que "sem falseamento" existe para capturar.

### D7 — Ponte de migração: full node dual-stack sobre blockstore único (não é tradutor de protocolo)

```
┌─ full node dual-stack (desktop/VPS — peso livre) ─┐
│  blockstore ÚNICO (obra assinada Ed25519)         │
│   ├── FullNode TRAMA   ← serve cliente-trama      │
│   └── FullNode LIBP2P  ← serve cliente-libp2p     │
└───────────────────────────────────────────────────┘
```

Dois servidores sobre o mesmo conteúdo — **não** um tradutor de fio (impossível: os protocolos são mutuamente surdos). É o mecanismo que torna `própria → libp2p` migrável sem flag day: full nodes viram dual-stack primeiro, clientes mobile rolam por update de app, full nodes largam o stack antigo por último. O E5 prova a ponte com os dois clientes baixando o mesmo capítulo verificado. A regra "um backend por build" (D1) vale para o **mobile**; o dual-stack de host é a exceção deliberada que viabiliza a rede em transição.

### D8 — Reuso do manifesto Ed25519; comparabilidade estrita; nome de trabalho "Trama"

O manifesto assinado (Ed25519 + `seq`, canônico) das três POCs é reusado sem revalidação — a verificação é o mesmo Kotlin no `:api`. Rig idêntico: Moto g(30) API 31, IP público `177.203.17.5` (faixa 4000-4999), check-host.net, hotspot/dados móveis para rede separada. Baselines de peso/dados vêm dos relatórios publicados, não de re-medição seletiva. O backend próprio chama-se **Trama** (malha/tecido ↔ enredo de história) nesta POC; "openp2p" foi descartado por colisão com projeto existente. O nome de produto fica fora do escopo (non-goal).

### D9 — Ordem: E0 → E1 → E2 → {E3, E4} → E5; o risco maior primeiro dentro de cada etapa

```
E0 interface+TCK (instrumento; sem ele nada é medível)
E1 paridade de client pela interface (barato — motores já provados)
E2 seam de full-node ⚑ (a Q1; lado servidor rust via FFI = maior trabalho novo)
E3 features no tempo   ┐ podem intercalar após E2
E4 matriz E2E 4×2      ┘ (E4 exige E2 completo nos dois backends)
E5 dual-stack + medições de custo (fecha Q9, Q2, Q4, Q12)
```

Se o E2 falhar (seam não unifica) ou estourar o veto no adapter libp2p, E3/E4/E5 degradam para rodar só no que existir e a POC encerra com **resultado negativo conclusivo** — Q1 respondida "não" já é o veredito (commit num backend).

## Risks / Trade-offs

- [O seam de full-node não unificar gossip×Kademlia — o risco central] → é a Q1; falhar é resultado válido e útil ("commit num backend"). O design mitiga escondendo expiry/republish dentro do backend (D2) e medindo o vazamento em vez de negá-lo (D4).
- [Lado servidor rust via FFI custar demais (novo: listen/provide/serve + build de host dylib)] → veto de 5 dias no adapter (D3); estourar = dado. O client-side já está provado (poc-03), o delta é só o servidor.
- [A abstração "passar" por ser rasa demais (interseção pobre que não serve o produto)] → a matriz E2E 4×2 (D6) obriga a interface a cobrir o ciclo completo do produto (publicar→anunciar→descobrir→servir→verificar) em condições reais; uma interface rasa não fecha as 8 células.
- [Viés pró-abstração (a POC quer que dê certo)] → limiares a priori (D5), inventário de vazamento exaustivo (D4), e a Q12 formulada contra a abstração ("commit e assumir o acoplamento é mais honesto?") — o relatório é obrigado a argumentar o contra.
- [Refatorar o poc-02 para trás do seam distorcer a baseline] → `poc02/` intocado como baseline viva; `:trama` é cópia refatorada em `poc04/`; regressão de peso ≤ +5% medida contra o relatório publicado.
- [Comparação de features futuras virar especulação] → E3 separa três classes: **executado** (hole-punch se o setup couber), **dado-só** (componentes wired, teste não roda), **só-design** (browser/WebRTC) — cada claim etiquetada com sua classe no relatório.
- [PoC "vazar" para produto] → `poc04/` fora dos módulos de produto; o relatório é o entregável; o código "serve de referência", não de fundação — se o Marco 2 adotar a interface, ela é re-escrita nos módulos de produto com revisão.

## Migration Plan

Não aplicável — código descartável, sem deploy nem produto afetado. Fechamento = `docs/poc04-report.md` escrito com Q1–Q12 respondidas e o veredito de estratégia; roadmap e relatórios anteriores referenciando a change; `poc04/` pode ser arquivado/removido depois (a interface, se aprovada, é referência para o design do módulo de rede do Marco 2).

## Open Questions

- O `FullNode` do rust-libp2p em desktop JVM via dylib de host (JNA) tem alguma classe de bug FFI que o Android (poc-03) não revelou? (registrar no E2)
- `announce` com semântica de TTL/refresh unificável precisa de parâmetro de tempo na interface, ou o default interno de cada backend basta? (decidir no E0, revisitar no E2 — candidato nº 1 a ponto de vazamento)
- O hole-punch DCUtR device↔device sem port-forward cabe no setup da POC (exige relay público próprio)? Se não, degrada para dado-só com componentes wired — como no poc-03. (decidir no E3)
- O TCK consegue cobrir expiry/republish de forma determinística (tempo virtual/TTLs curtos) sem falsear o comportamento real? (decidir no E0; a matriz S1 cobre o caso real de qualquer forma)
- A capability-flag é `Set<Capability>` consultável ou metadado de build (o app nem pergunta)? (decidir no E0; impacta o inventário de vazamento)
