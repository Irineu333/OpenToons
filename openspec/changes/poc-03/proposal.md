# Proposta: poc-03 — libp2p de referência (go-libp2p e rust-libp2p) via bindings nativos

## Why

O poc-01 testou o libp2p **na sua encarnação mais fraca** — o port JVM (nabu/jvm-libp2p), com QUIC instável, sem DCUtR e descoberta na Amino bloqueada por quatro bugs upstream. O poc-02 então venceu esse adversário enfraquecido em todas as métricas medidas e recomendou stack própria para o Marco 2. Fica um buraco honesto na comparação: **a implementação de referência do libp2p — go-libp2p (que roda o kubo/IPFS, Filecoin) e rust-libp2p (Ethereum, Polkadot) — nunca entrou no ringue.** "A stack própria venceu o port JVM capenga" não é o mesmo que "a stack própria vence o libp2p".

O poc-03 fecha esse buraco: um **benchmark justo do libp2p de referência**, contra o mesmo critério E2E do Marco 0 e o mesmo rig do poc-01/02, para colher **dados comparáveis** — não para decidir stack agora. O caminho para embarcar go/rust no mobile e no KMP é via **bindings nativos** (gomobile; UniFFI + cargo-ndk) — exatamente o "plano B (gomobile/UniFFI)" que o poc-01 declarou nunca ter precisado. O custo desse binding (tamanho de APK, complexidade de build, superfície FFI) é o principal dado a coletar, porque ataca justamente os pontos onde o poc-02 venceu (0,96 MB de APK, KMP em Kotlin puro).

## What Changes

- Novo módulo descartável `poc03/` no repositório (fora dos módulos de produto `shared`/`desktopApp`); `poc01/` e `poc02/` permanecem intocados como linha de base.
- **E1 — Binding (2 variantes comparadas, o risco existencial):** embarcar o libp2p de referência no Android e no KMP via gerador de binding pronto + **facade fino nosso** (Tier B) — (a) **go-libp2p** via `gomobile bind` → `.aar` + `c-shared` para desktop; (b) **rust-libp2p** via UniFFI + `cargo-ndk` → Kotlin + `.so`. Cross-compilação por ABI, esforço em dias e LoC do facade medidos. Cada variante tem **veto de esforço de 5 dias úteis**.
- **E2 — Superfície FFI + troca de blocos:** API mínima cruzando a fronteira (`dial`, `resolve`, `get-blocks`, `verify`). Blocos por **Request-Response** (nativo em go e rust) para manter **paridade** entre as variantes e reusar o manifesto assinado Ed25519 do poc-01. Bitswap não está em nenhum core de libp2p (go → `boxo`; rust → sem oficial): entra só como **bônus de interop só-go**.
- **E3 — Descoberta com Kademlia real:** o DHT de referência (battle-tested, roda o IPFS) na **rede-bootstrap própria** — descoberta fria como o E5 do poc-01. RTTs por lookup, convergência e tráfego medidos sobre a implementação real, sem simulação. A **Amino** é apenas uma **tentativa registrada** (dado histórico vs poc-01), não critério.
- **E4 — E2E (mesmo critério do Marco 0):** capítulo assinado num nó público é descoberto por descoberta fria e baixado por um mobile em outra rede, com verificação de assinatura e rejeição de adulterado — provando que o libp2p de referência fecha o mesmo critério, agora com QUIC e DHT que de fato funcionam.
- **E5 — Medições comparativas:** mesma sessão de 30 min, mesmo dispositivo (Moto g(30)) e mesmos limiares do poc-01/02 (< 5% bateria, < 20 MB dados), mais **tamanho de APK por ABI**, LoC do facade e nº de dependências — tabela lado a lado nabu × própria × go × rust. Mais os **dados que nenhuma POC anterior colheu**: hole-punch DCUtR (TCP), estabilidade de QUIC em dials paralelos e interop com kubo (bônus só-go).
- **Relatório final** `docs/poc03-report.md` com as matrizes de decisão (go × rust; binding pronto × próprio; referência × própria × nabu), medições e os dados coletados — o artefato durável; o código de `poc03/` pode ser apagado depois.

## Capabilities

### New Capabilities

- `poc3-validation`: os experimentos comparativos do poc-03 (E1–E5) e o relatório — o que cada experimento precisa demonstrar e medir para ser conclusivo (positiva ou negativamente), incluindo os limiares fixados a priori, o veto de esforço do binding e os dados só-coletados (interop, NAT, QUIC).

### Modified Capabilities

(nenhuma — não existem specs ativas; as specs do poc-01 e poc-02 foram arquivadas com seus changes)

## Impact

- **Código:** novo módulo Gradle `poc03/` (JVM/KMP + alvo Android mínimo) mais o facade nativo em Go (`poc03/go-facade`) e Rust (`poc03/rust-facade`); nenhum código de produto é alterado. `poc01/` e `poc02/` intocados como baselines vivas.
- **Toolchain:** Go + `gomobile` (Android NDK), Rust + `cargo-ndk` + UniFFI — cross-compilação para as ABIs do Android (arm64-v8a, armeabi-v7a, x86_64) e libs de host para o desktop. É a maior mudança de infraestrutura das três POCs.
- **Dependências:** go-libp2p (+ `boxo` só para o bônus Bitswap), rust-libp2p; runtime Go/`.so` Rust embarcados no APK. O peso disso é dado central, não incidental.
- **Infra:** rede-bootstrap própria com nós de endereço público (mesmo port forwarding do poc-01/02); dispositivo físico Moto g(30) para comparabilidade; um kubo local como par de interop (bônus só-go); a Amino só para a tentativa registrada.
- **Docs:** `docs/poc03-report.md` novo; roadmap, `poc01-report.md` e `poc02-report.md` ganham referência a esta change.
- **Risco assumido:** o binding/cross-compile custar mais do que qualquer coisa das POCs anteriores (que eram Kotlin puro). O **veto de esforço** (5 dias úteis por variante, a priori no design) transforma o estouro em **resultado válido** — "o libp2p de referência é excelente mas inshippável por binding a custo aceitável" é uma conclusão possível e útil. A decisão de stack **não** é tomada aqui; a POC só alimenta decisões futuras com dados.
