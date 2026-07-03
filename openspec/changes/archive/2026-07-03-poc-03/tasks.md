# Tasks: poc-03 — libp2p de referência via bindings nativos

> Ordem conforme design D8: E1 primeiro (risco existencial do binding, com veto de esforço); sem paralelo possível — E2/E3/E4/E5 dependem do facade embarcado. Registrar **esforço real (dias)** ao concluir cada grupo — é dado do relatório.

## 1. Setup

- [x] 1.1 Criar módulo `poc03/` (KMP + alvo Android mínimo minSdk 26) fora dos módulos de produto; `poc01/` e `poc02/` intocados como baselines (D6)
- [x] 1.2 Instalar/validar o toolchain de binding: Go + `gomobile` + NDK; Rust + `cargo-ndk` + UniFFI (registrar atrito de setup por toolchain)
- [x] 1.3 Referenciar o `Manifest.kt` + verificação Ed25519 do poc-01 no lado Kotlin (D7) — a verificação não cruza a fronteira FFI
- [x] 1.4 Registrar no relatório-rascunho os limiares a priori (D5) e os prontos Tier A avaliados e descartados (gomobile-ipfs, iroh-ipfs) antes de qualquer medição

## 2. E1a — Binding go-libp2p (gomobile) (veto: 5 dias úteis)

- [x] 2.1 Escrever o facade Go mínimo expondo `dial`/`resolve`/`get-blocks`/`verify` sobre go-libp2p (Noise + TCP/QUIC + Kademlia)
- [x] 2.2 `gomobile bind` → `.aar` para as ABIs alvo (arm64-v8a, armeabi-v7a, x86_64) + `c-shared` para o desktop; registrar complexidade de build e ABIs cross-compiladas
- [x] 2.3 Carregar o `.aar` no app e inicializar o nó no dispositivo físico (Moto g(30), API 31) sem crash; registrar tamanho do binário adicionado por ABI
- [x] 2.4 Registrar esforço real (dias), LoC do facade e ajuste ao KMP (Java-ish do gomobile)

## 3. E1b — Binding rust-libp2p (UniFFI) (veto: 5 dias úteis)

- [x] 3.1 Escrever o facade Rust mínimo com `#[uniffi::export]` expondo a mesma superfície (`dial`/`resolve`/`get-blocks`/`verify`) sobre rust-libp2p (Noise + TCP/QUIC + Kademlia)
- [x] 3.2 `cargo-ndk` + UniFFI → `.so` + bindings Kotlin para as ABIs alvo + `cdylib` de host para o desktop; registrar complexidade de build
- [x] 3.3 Carregar o `.so` no app e inicializar o nó no dispositivo físico sem crash; registrar tamanho do binário adicionado por ABI
- [x] 3.4 Registrar esforço real (dias), LoC do facade e ajuste ao KMP (Kotlin idiomático do UniFFI, caminho `expect/actual`)

## 4. Decisão do binding (fecha E1)

- [x] 4.1 Verificar que ambas as variantes expõem a MESMA superfície FFI ao app (E2/E3/E4 rodam sobre qualquer uma sem mudar o app)
- [x] 4.2 Preencher a matriz comparativa do design D2 (completude, KMP, peso, churn, interop, hole-punch) — ou registrar veto (por variante ou duplo → recomendação "não seguir por binding" e encerrar a PoC pelo cenário de falha conclusiva)

## 5. E2 — Superfície FFI + troca de blocos por Request-Response

- [x] 5.1 Implementar `get-blocks` por Request-Response nas duas variantes (paridade, D3); download do capítulo de 3 blocos do poc-01
- [x] 5.2 Verificar em Kotlin (fora da fronteira FFI) a assinatura Ed25519 do manifesto + hash de cada bloco; reconstruir o capítulo íntegro
- [x] 5.3 Exercitar a fronteira FFI sob concorrência e ciclos de vida do app; registrar classes de bug FFI (threading/memória/lifecycle) encontradas
- [x] 5.4 Bônus só-go: habilitar Bitswap via `boxo` contra um kubo local e registrar o resultado de interop (want/have de bloco)
- [x] 5.5 Medir throughput do capítulo vs números de nabu (poc-01) e própria (poc-02); registrar

## 6. E3 — Descoberta com Kademlia real na rede-bootstrap própria

- [x] 6.1 Subir a rede-bootstrap própria (nós plenos com endereço público, identidades determinísticas por porta, topologia do E5 do poc-01) publicando o capítulo de teste
- [x] 6.2 Descoberta fria do cliente (só bootstrap + obraId) via Kademlia real: medir RTTs por lookup (≤ 3 RTTs) e convergência pós-churn
- [x] 6.3 Verificar modo client puro: cliente não armazena, não roteia, não aceita entrada (ADR-0005)
- [x] 6.4 Tentativa registrada na Amino: conecta? resolve? — registrar como dado histórico vs poc-01, sem ser critério

## 7. E4 — E2E do Marco 0 com o libp2p de referência

- [x] 7.1 Nó pleno com endereço público manual (mesmo port forwarding do poc-01/02) no ar; alcançabilidade externa comprovada (check-host.net)
- [x] 7.2 App no dispositivo físico, em outra rede (hotspot/dados móveis): descoberta fria → disca o publicador nunca informado → baixa → verifica assinatura → reconstrói o capítulo
- [x] 7.3 Rejeição de adulterado: bloco com hash divergente e manifesto com assinatura inválida são rejeitados e reportados no app

## 8. E5 — Medições comparativas e dados só-coletados

- [x] 8.1 Sessão de 30 min no Moto g(30), mesmo roteiro do poc-01/02 (`batterystats` + tráfego por UID): bateria < 5%, dados < 20 MB, lado a lado com nabu (0,03% / 1,09 MB) e própria (0,012% / 0,13 MB)
- [x] 8.2 Medir handshake (< 1 s) e reconexão (< 500 ms, incluindo QUIC 0-RTT se houver) em rede real; lookup frio ≤ 3 RTTs
- [x] 8.3 Empacotar o app com split de ABI: tamanho por ABI (≤ 20 MB) e delta bruto vs 0,96 MB do poc-02, por variante; LoC do facade; nº de dependências
- [x] 8.4 Dados só-coletados: hole-punch DCUtR (device↔device sem port-forward, TCP); estabilidade de QUIC em dials paralelos; interop com kubo local (bônus só-go) — registrar sem limiar

## 9. Relatório e fechamento

- [x] 9.1 Escrever `docs/poc03-report.md`: resultados por experimento, as matrizes de decisão preenchidas (go × rust; binding pronto × próprio; referência × própria × nabu), esforço real por experimento, respostas às questões abertas do design, limiares vs medições e os dados só-coletados
- [x] 9.2 Atualizar `docs/roadmap.md`, `docs/poc01-report.md` e `docs/poc02-report.md` com referência ao poc-03
