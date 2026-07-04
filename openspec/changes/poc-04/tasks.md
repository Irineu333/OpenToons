# Tasks: poc-04 — abstração de P2P com backend trocável (Trama × rust-libp2p)

> Ordem conforme design D9: E0 (interface+TCK, o instrumento) → E1 (client, barato) → E2 (full-node, a Q1 e o maior trabalho novo, com veto) → E3/E4 → E5. Registrar **esforço real (dias)** ao concluir cada grupo — é dado do relatório (Q12). Restrição transversal: implementação real dos dois lados, sem falseamento (D3) — sem simulação in-process nas células que contam, sem servidor fora da interface, `adb reverse` só em smoke test.

## 1. Setup

- [ ] 1.1 Criar módulos `poc04/api`, `poc04/trama`, `poc04/libp2p`, `poc04/android` (build variants `trama`/`libp2p` — um backend por variant) e `poc04/node` (full nodes de host), fora dos módulos de produto; `poc01/`–`poc03/` intocados como baselines (D8)
- [ ] 1.2 Copiar o facade rust do poc-03 para `poc04/rust-facade` (o original fica como baseline); validar que o toolchain do poc-03 (cargo-ndk + UniFFI + NDK) ainda builda, e acrescentar o alvo de host (dylib macOS arm64) — registrar atrito
- [ ] 1.3 Referenciar o manifesto Ed25519 + verificação das POCs anteriores no `poc04/api` (D8) — `verify` em Kotlin, fora do seam
- [ ] 1.4 Registrar no relatório-rascunho as questões Q1–Q12 e os limiares a priori (D5) antes de qualquer medição

## 2. E0 — Interface neutra + TCK (o instrumento)

- [ ] 2.1 Definir `P2pBackend` (client: `dial`/`resolve`/`getBlocks`/`close`) e `FullNode` (servidor: `start`/`publish`/`announce`/`serve`/`stop`) com tipos neutros (`ObraId`, `ContentId` sha256, `Provider`, `BootstrapAddr`, `Block`, `Manifest`); expiry/republish internos ao backend (D2)
- [ ] 2.2 Decidir e documentar o mecanismo de capability-flag (`Set<Capability>` consultável × metadado de build) — cada flag conta no inventário de vazamento (D4)
- [ ] 2.3 Escrever o TCK ANTES de qualquer adapter: resolve, download, verificação, rejeição de adulterado (bloco corrompido; chave errada), expiry/republish — mesmos vetores para qualquer backend
- [ ] 2.4 Revisar a interface contra os dois backends planejados: nenhum conceito de backend na superfície (CID, multiaddr, PeerId, digest de gossip ficam nos adapters) — responde Q3 no nível de design

## 3. E1 — Paridade de client pela interface (veto: 5 dias úteis por adapter, compartilhado com E2)

- [ ] 3.1 Adapter de client Trama: portar o cliente do poc-02 (Noise XX + RPC + descoberta gossip) para trás de `P2pBackend` — refatoração honesta, não wrapper
- [ ] 3.2 Adapter de client rust-libp2p: ligar o facade do poc-03 (`dial`/`resolve`/`getBlocks` via UniFFI) a `P2pBackend`, conversões CID/multiaddr/PeerId internas ao adapter
- [ ] 3.3 Rodar o TCK de client nos dois adapters: 100% verde nos dois, mesmos vetores, zero branch por backend no consumidor — fecha Q5 (parte client)

## 4. E2 — Seam de full-node (a Q1; o maior trabalho novo)

- [ ] 4.1 Full node Trama atrás de `FullNode`: refatorar o nó do poc-02 (membership + PEX + anúncios com TTL + malha de saída) para ser dirigido pela interface — client e servidor pelo mesmo seam
- [ ] 4.2 Estender o facade rust com o lado servidor: `listen` (TCP+QUIC), Kademlia `Mode::Server`, `start_providing`, servir blocos por Request-Response — expor via UniFFI e registrar o atrito FFI novo
- [ ] 4.3 Buildar o facade estendido para host (dylib) e dirigir o full node rust-libp2p de desktop JVM pela interface Kotlin `FullNode`; registrar classes de bug FFI do host que o Android não revelou
- [ ] 4.4 Rodar o TCK de full-node nos dois backends (publish + announce + resolve por client do mesmo backend + expiry/republish com TTLs curtos); registrar cada ponto onde a interface precisou de ajuste — candidatos ao inventário de vazamento
- [ ] 4.5 Fechar a Q1: a interface unificou sem método específico de backend? Se não (ou se o veto estourou), registrar a evidência e encerrar a PoC pelo cenário de resultado negativo conclusivo (D9)

## 5. E3 — Features no tempo (curto/médio/longo)

- [ ] 5.1 Verificar que `resolve(obraId)` esconde o mecanismo de descoberta: mesmo app resolve via gossip (Trama) e via Kademlia (libp2p) sem perceber a diferença — migração gossip→DHT invisível (Q6)
- [ ] 5.2 Hole-punch DCUtR/relay: avaliar se o teste device↔device sem port-forward cabe no setup (exige relay próprio); executar se couber, senão registrar como dado-só com componentes wired (relay_client + dcutr no facade) — etiquetar a classe de evidência
- [ ] 5.3 Provar "abaixo do seam": ganhar uma capability só-libp2p trocando a build variant com 0 linha de app alterada (Q8)
- [ ] 5.4 Sonda só-design: a interface expressa um alvo browser/WebRTC sem mudança de assinatura? Registrar análise (não executar)
- [ ] 5.5 Dimensionar o "construir do zero" (Q7): estimativa fundamentada de esforço para a Trama ganhar hole-punch coordenado, DHT de produção e transportes plugáveis — cada item com classe de evidência

## 6. E4 — Matriz E2E 4×2 (o critério duro; mesmo app, zero branch)

- [ ] 6.1 S1 × Trama: 4 full nodes em processos separados (bootstrap A + publicador P + R1 + R2); malha converge, anúncio propaga; matar P → provider expira; reviver P → republish o traz de volta
- [ ] 6.2 S1 × libp2p: mesma topologia e mesmo roteiro com Kademlia real (provide K-closest, republish); mesmas asserções
- [ ] 6.3 S2 × os dois backends: app no Moto g(30) baixa capítulo real (manifesto Ed25519 + blocos) de um full node de cada backend; verificação + rejeição de adulterado (bloco corrompido; chave errada)
- [ ] 6.4 S3 × os dois backends: app conhece SÓ A + obraId; descobre P (nunca informado) via A, disca e baixa; P confirma por log a conexão vinda do app
- [ ] 6.5 S4 × os dois backends: full nodes no IP público (port forwarding 4000-4999; alcançabilidade por check-host.net); app em dados móveis/hotspot de outra operadora fecha o ciclo completo (descoberta fria → download → verificação → rejeição) — o critério do Marco 0, duas vezes, só trocando a build variant
- [ ] 6.6 Auditar as 8 células: nenhuma passou com simulação in-process, servidor fora da interface, `adb reverse` ou branch por backend — célula que exigiu isso é registrada como falha (evidência contra a Q1)

## 7. E5 — Ponte dual-stack + custo da abstração

- [ ] 7.1 Full node dual-stack de host: os dois `FullNode` sobre o MESMO blockstore; cliente-Trama e cliente-libp2p baixam a mesma obra — bytes idênticos + assinatura OK nos dois (Q9)
- [ ] 7.2 Medir peso: APK release+R8 da variant `trama` vs 0,96 MB (poc-02) e da variant `libp2p` por ABI vs 8–11 MB/ABI (poc-03) — regressão ≤ +5%; confirmar que cada APK contém exatamente um backend (Q4)
- [ ] 7.3 Medir LoC de cola por adapter (≤ ~150, fora do motor) e LoC do `api`; registrar esforço real por adapter vs o veto
- [ ] 7.4 Compilar o inventário exaustivo de pontos de vazamento (total, natureza, localização): ≤ 3, todos capability-flag documentada = seam saudável (Q2); qualquer branch por backend no app = reprovação
- [ ] 7.5 Sanidade de latências no rig real (handshake < 1 s; reconexão < 500 ms; lookup frio ≤ 3 RTTs) — comparabilidade com poc-02/03, não é o foco

## 8. Relatório e fechamento

- [ ] 8.1 Escrever `docs/poc04-report.md`: Q1–Q12 respondidas uma a uma com evidência etiquetada (executado / dado-só / só-design), limiares vs medições, matriz E2E preenchida, inventário de vazamento, esforço real por experimento
- [ ] 8.2 Declarar o veredito de estratégia (Q11): `própria` / `rust-libp2p` / `própria → rust-libp2p condicional` — se condicional, definir o gatilho concreto e observável (Q10) e registrar o julgamento da Q12 (o imposto da opcionalidade vale?)
- [ ] 8.3 Atualizar `docs/roadmap.md` e os relatórios anteriores com referência ao poc-04; registrar a recomendação para o design do módulo de rede do Marco 2
