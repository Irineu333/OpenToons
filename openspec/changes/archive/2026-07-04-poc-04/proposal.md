# Proposta: poc-04 — abstração de P2P com backend trocável (Trama × rust-libp2p)

## Why

As três POCs deixaram uma decisão deliberadamente aberta: o poc-02 recomendou a **stack própria** (venceu em todas as métricas — 0,96 MB de APK, 0,13 MB/sessão, zero bug de terceiros) e o poc-03 provou que o **rust-libp2p é shippável** (8–11 MB/ABI, dentro do teto) mas se recusou a decidir stack. A própria ganha **hoje** (peso, controle, KMP puro); o libp2p de referência ganha **depois** — as features difíceis do Marco 4 (hole-punch DCUtR/relay v2, transportes plugáveis, Kademlia de produção quando o gossip atingir o gatilho de ≈ 5.000 registros) já existem lá e teriam que ser construídas do zero na própria. Commitar numa stack agora acopla o app a essa aposta; a alternativa é **não acoplar**: uma fronteira de módulos onde a implementação P2P é um detalhe trocável em build-time, e a estratégia `própria → rust-libp2p` vira uma **opção condicional de-riscada** em vez de uma reescrita futura.

O poc-04 testa se essa abstração **segura de verdade** — não em teoria, mas com **implementação real dos dois lados, sem falseamento**: os dois backends completos (client **e** full node) atrás da mesma interface, exercitados pela mesma matriz E2E com múltiplos nós, transferência real para o mobile, descoberta transitiva e internet real (rede separada). O poc-03 já provou o seam de *client* (dial/resolve/getBlocks); o teste de verdade agora é o **seam de full-node** (announce/serve/republish), onde gossip e Kademlia mais divergem. Se segurar, a migração que o poc-02 já previu vira um flag de build; se vazar, o veredito honesto é "commit num backend, o desacoplamento não paga o imposto".

## What Changes

- Novo módulo descartável `poc04/` no repositório (fora dos módulos de produto `shared`/`desktopApp`); `poc01/`–`poc03/` permanecem intocados como linhas de base. Código descartável, **mas serve de referência de arquitetura** para o Marco 2 (a interface, se aprovada, informa o design do módulo de rede real).
- **E0 — Interface + TCK (o instrumento):** `poc04/api` em Kotlin commonMain — interface `P2pBackend` (client) + `FullNode` (servidor) com **tipos 100% neutros** (`ObraId`, `ContentId`=sha256, `Provider`, `Block`, `Manifest`, `BootstrapAddr`), definida ANTES de qualquer adapter, mais a **suíte de conformidade (TCK)** que roda idêntica em qualquer backend. `verify` (Ed25519 + hash) fica no `api`, fora do seam (D7 do poc-03).
- **E1 — Backend Trama (a própria, real):** `poc04/trama` — a stack do poc-02 (Noise XX + RPC de frames + membership/gossip) **por trás da interface**, client e full node. Refatoração honesta do nó do poc-02, não wrapper de fachada.
- **E2 — Backend rust-libp2p (real, com lado servidor novo):** `poc04/libp2p` — o facade do poc-03 **estendido com o lado full-node via FFI** (listen + Kademlia server + provide + serve-blocks), dirigido pela mesma interface Kotlin. Exige build de host (dylib) para o full node em desktop JVM, além dos `.so` Android. É o maior pedaço de trabalho novo. **Veto de esforço de 5 dias úteis por adapter.**
- **E3 — Features no tempo (curto/médio/longo):** para cada feature futura, testar se cai **abaixo do seam** (ganhar trocando de backend = 0 diff no app) ou **vaza**: hole-punch DCUtR/relay (executar se o setup couber; senão dado-só), migração gossip→DHT (o app percebe?), transporte browser/WebRTC (sonda só-design).
- **E4 — Matriz E2E (4 cenários × 2 backends = 8 células, todas reais):** com o **mesmo app e zero branch por backend** — (S1) malha de 4 nós plenos em processos separados, incluindo morte do publicador + expiry/republish; (S2) full node servindo capítulo real ao Moto g(30) com verificação e rejeição de adulterado; (S3) descoberta transitiva — o app conhece SÓ o bootstrap e descobre o publicador nunca informado; (S4) internet real — dispositivo em dados móveis (rede separada) → IP público. Sem simulação in-process; sockets, processos e dispositivo reais.
- **E5 — Ponte de migração + custo da abstração:** full node **dual-stack** (os dois stacks sobre o MESMO blockstore, desktop/VPS onde peso é livre) servindo cliente-Trama E cliente-libp2p — a prova de que `própria → libp2p` é factível **sem flag day**; mais as medições do imposto: regressão de peso por backend (≤ +5% vs baselines 0,96 MB e 8–11 MB/ABI), LoC de cola por adapter, e o **inventário de pontos de vazamento** (a métrica-mãe: ≤ 3, todos capability-flag documentada). O mobile **nunca** carrega os dois backends (build variant escolhe um).
- **Relatório final** `docs/poc04-report.md` respondendo Q1–Q12 (seam segura? o que o libp2p dá no tempo? migração factível? o imposto vale?) com o **veredito de estratégia**: `própria`, `rust-libp2p` ou `própria → rust-libp2p condicional a gatilho`.

## Capabilities

### New Capabilities

- `poc4-validation`: os experimentos do poc-04 (E0–E5) e o relatório — o que cada experimento precisa demonstrar e medir para ser conclusivo (positiva ou negativamente), incluindo as questões Q1–Q12 fixadas a priori, os limiares de vazamento/peso/paridade, a matriz E2E 4×2 como critério duro de realidade e o veto de esforço por adapter.

### Modified Capabilities

(nenhuma — não existem specs ativas; as specs do poc-01–03 foram arquivadas com seus changes)

## Impact

- **Código:** novos módulos Gradle `poc04/api`, `poc04/trama`, `poc04/libp2p`, `poc04/android` (app de teste com build variants — um backend por variant) e `poc04/node` (full nodes de host); o facade rust do poc-03 é **copiado e estendido** em `poc04/rust-facade` (o original fica intocado como baseline). Nenhum código de produto alterado.
- **Toolchain:** a mesma do poc-03 (Rust + cargo-ndk + UniFFI + NDK já instalados) **mais o alvo de host** (dylib macOS arm64 para o full node desktop JVM). Go/gomobile sai de cena (o poc-03 já vetou o go pelo peso).
- **Dependências:** rust-libp2p (herdada do poc-03); a Trama não adiciona nenhuma (bcprov + kotlinx-serialization já validadas no poc-02).
- **Infra:** o mesmo rig provado três vezes — port forwarding no IP público (faixa 4000-4999), check-host.net para alcançabilidade, Moto g(30) físico, hotspot/dados móveis para a rede separada.
- **Docs:** `docs/poc04-report.md` novo; roadmap e relatórios anteriores ganham referência a esta change.
- **Risco assumido:** (a) o seam de full-node não unificar gossip e Kademlia — resultado **válido e útil** ("a abstração não segura; commit num backend"); (b) o lado servidor via FFI custar mais que o veto — idem (o veto transforma estouro em conclusão); (c) risco de terceiros baixo: as duas stacks já rodaram E2E nos POCs anteriores; o novo é a *fronteira*, não os motores. A decisão de stack do Marco 2 **é** informada por esta POC — diferente do poc-03, aqui o relatório termina com recomendação de estratégia.
