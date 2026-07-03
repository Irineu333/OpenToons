# Design: poc-03 — libp2p de referência via bindings nativos

## Context

O poc-01 ([relatório](../../../docs/poc01-report.md)) testou o libp2p pela via JVM (nabu/jvm-libp2p): QUIC instável em dials paralelos, sem DCUtR, Amino bloqueada por quatro bugs upstream. O poc-02 ([relatório](../../../docs/poc02-report.md)) construiu uma stack própria e venceu esse adversário em bateria, dados, APK (0,96 MB) e LoC, recomendando-a para o Marco 2. Mas a implementação **de referência** do libp2p — go-libp2p (kubo/IPFS, Filecoin) e rust-libp2p (Ethereum, Polkadot) — nunca foi medida. O poc-03 preenche esse buraco com um benchmark justo, cujo produto é **conhecimento comparável**, não features; a decisão de stack **não** é tomada aqui.

Fatos confirmados na documentação atual (Context7, jul/2026) que moldam o design:

- **Bitswap não está em nenhum core de libp2p.** rust-libp2p v0.57 documenta 14 protocolos (TCP, QUIC, Noise, TLS, Yamux, **Kademlia**, Gossipsub, Relay, **DCUtR**, AutoNAT, **Request-Response**, …) — Bitswap não está na lista. Em go, Bitswap vive no `boxo` (`ipfs/boxo/bitswap`), dependência à parte. → troca de blocos por **Request-Response** nos dois para manter paridade.
- **DCUtR (hole-punch) existe nos dois**, mas o QUIC hole-punch do rust ainda está no roadmap (TCP DCUtR funciona). → dado de NAT coletado sobre TCP.
- **Nenhum dos dois publica binding mobile pronto.** Não há `.aar`/`.framework` de "libp2p mobile" para importar. → o caminho é gerador de binding pronto + facade fino nosso.

A stack de referência entrega pronto quase tudo que o poc-02 construiu à mão (Noise, Kademlia, muxing, QUIC); em troca, exige atravessar a fronteira FFI e o cross-compile — o risco e o custo desta POC:

```
                     go-libp2p            rust-libp2p
Descoberta           Kademlia real        Kademlia real          ← E3
Blocos               Request-Response     Request-Response       ← E2 (paridade)
                     (+ boxo/Bitswap:     (sem Bitswap oficial)
                      bônus só-go interop)
Canal                Noise (pronto)       Noise (pronto)
Transporte           TCP + QUIC           TCP + QUIC
BINDING              gomobile → .aar      UniFFI+cargo-ndk → .so  ← E1 (risco real)
Identidade/Manifesto Ed25519 do poc-01 (D8), inalterado          ← pronto
```

## Goals / Non-Goals

**Goals:**

- Gerar **dados comparativos para decisão**, com limiares fixados a priori: (a) go-libp2p × rust-libp2p; (b) binding pronto × facade próprio; (c) libp2p de referência × stack própria (poc-02) × nabu (poc-01).
- Provar que o libp2p de referência fecha o **mesmo critério E2E do Marco 0** (nó público → mobile em outra rede, descoberta fria, verificação, rejeição), agora com QUIC e DHT que de fato funcionam.
- Coletar os **dados que nenhuma POC anterior colheu**: hole-punch DCUtR (TCP), estabilidade de QUIC em dials paralelos, interop com kubo (bônus só-go).
- Produzir `docs/poc03-report.md` com as matrizes preenchidas — o artefato durável.
- Comparabilidade estrita com poc-01/02: mesmo dispositivo, mesmo roteiro de sessão, mesmos limiares de bateria/dados.

**Non-Goals:**

- Decidir a stack de rede do Marco 2 — a POC alimenta a decisão, não a toma.
- Qualquer código de produto — nada em `shared`/`desktopApp`.
- Descoberta na Amino em escala como critério — foco é a **rede-bootstrap própria**; a Amino é só tentativa registrada (dado histórico vs poc-01).
- Bitswap no rust — ele não existe oficialmente; blocos por Request-Response nos dois (paridade). Bitswap+boxo é bônus só-go.
- Hole-punch/relay como requisito de produto (ADR-0006 mantém para o marco 4) — aqui é dado coletado, não critério.
- Formato final de wire/manifesto, robustez de produção, hardening — código descartável com o rigor mínimo para conclusões críveis.

## Decisions

### D1 — "Bindings" = gerador pronto + facade fino nosso (Tier B); prontos de nível-alto só registrados

Há três caminhos, e a documentação atual descarta os extremos:

```
Tier A  importar libp2p mobile pronto → NÃO EXISTE p/ libp2p cru.
        Mais próximo: gomobile-ipfs (berty), iroh-ffi — empacotam IPFS/iroh
        inteiro (pesado, opinativo), não o facade mínimo. Só REGISTRADOS.
Tier B  gerador pronto + facade nosso  ← ESCOLHIDO
        go:   facade Go pequeno → `gomobile bind` → .aar + c-shared
        rust: facade Rust #[uniffi::export] → cargo-ndk → Kotlin + .so
Tier C  JNI/C na unha, do zero → reinventa gomobile/UniFFI. Descartado.
```

Tier B: nós donos da superfície (`dial`/`resolve`/`get-blocks`/`verify`); o gerador cuida do marshalling. O custo do facade (LoC, complexidade de build) **é dado do E2**. Os prontos Tier A entram no relatório como "atalho avaliado e por que não seguimos" (peso/escopo), do mesmo jeito que a Amino.

### D2 — E1 implementa e compara AMBAS as variantes (go via gomobile, rust via UniFFI)

As duas entregam o mesmo libp2p de referência; diferem em completude, peso e ajuste ao KMP. Análise a priori que o experimento confirma ou refuta com dados:

| Critério | E1a — go-libp2p (gomobile) | E1b — rust-libp2p (UniFFI) |
|---|---|---|
| Completude protocolar | Maior — Kademlia + **Bitswap via boxo** (é o kubo) | Kademlia sim; **sem Bitswap oficial** — blocos por Request-Response |
| KMP fit | `.aar` Android; desktop `c-shared` por host; Java-ish | UniFFI gera Kotlin idiomático; caminho `expect/actual` mais limpo |
| Peso do binário | runtime Go pesado (GC), grande por ABI | sem GC, `.so` mais enxuto por ABI |
| Churn de API | estável | churna mais (mais glue de manutenção) |
| Interop ecossistema | kubo/IPFS de fábrica (bônus) | request-response próprio; interop IPFS não trivial |
| Hole-punch | DCUtR TCP + QUIC | DCUtR TCP; QUIC no roadmap |

Leitura: a hipótese a priori (do usuário) é "go mais completo, rust melhor KMP" — só os dados desempatam. Cada variante tem **veto de esforço de 5 dias úteis** (D5) para chegar ao E2E no dispositivo; estourar ambos é resultado válido ("o libp2p de referência é inshippável por binding a custo aceitável").

### D3 — Blocos por Request-Response (paridade); Bitswap+boxo é bônus só-go de interop

Todo o tráfego da OpenToons é request/response e o manifesto assinado (D7) é independente de stack. Como Bitswap não está em nenhum core e o rust não tem versão oficial, usar `Request-Response` (nativo nos dois) mantém as variantes comparáveis e reusa o formato de blocos do poc-01/02. O E2 mede o download do mesmo capítulo de 3 blocos. O go **também** roda Bitswap via `boxo` **apenas** para o teste de interop com um kubo local (bônus, não critério) — o único ponto onde a POC toca o ecossistema IPFS de fato.

### D4 — Descoberta é Kademlia real na rede-bootstrap própria; Amino só registrada

Diferente do poc-02 (que simulou gossip × Kademlia porque não tinha uma DHT pronta), aqui o Kademlia de referência é real e battle-tested — não se simula, mede-se. O E3 roda a descoberta fria na **rede-bootstrap própria** (topologia do E5 do poc-01: nós plenos com endereço público, identidades determinísticas por porta). A **Amino** entra como uma **tentativa registrada** — o poc-01 falhou nela por bugs do nabu; o libp2p de referência deve conectar/resolver, e isso é dado útil, mas não é o critério de conclusão. Métricas: RTTs por lookup do cliente, convergência pós-churn, tráfego — sobre a implementação real.

### D5 — Limiares fixados a priori (comparabilidade + veto de esforço + dados só-coletados)

Definidos ANTES de qualquer medição; ajustes exigem justificativa registrada no relatório:

| Métrica | Cenário | Limiar |
|---|---|---|
| Bateria | Mesma sessão do poc-01/02: 30 min, lookups periódicos, Moto g(30) | **< 5%** |
| Dados móveis | Mesma sessão, tráfego do UID além do conteúdo | **< 20 MB** |
| Handshake | Primeira conexão, dispositivo físico, rede real | **< 1 s** |
| Reconexão | Reconexão subsequente ao mesmo nó (QUIC 0-RTT se houver) | **< 500 ms** |
| Lookup frio (Kademlia) | Cliente resolve providers a partir só do bootstrap | **≤ 3 RTTs** |
| APK por ABI | App completo com o binding, com split de ABI (vs 0,96 MB do poc-02) | **≤ 20 MB por ABI** (delta bruto registrado) |
| Veto de esforço | Cada variante do E1 (go; rust) até o E2E no dispositivo | **≤ 5 dias úteis** cada |

**Dados só-coletados (sem limiar — o ponto é colher, não passar):** hole-punch DCUtR device↔device sem port-forward (TCP); estabilidade de QUIC em dials paralelos (o que quebrou o jvm-libp2p); interop — conecta na Amino? alcançável de um kubo local via ipfs-check? (bônus só-go).

O limiar de APK reconhece que o binding é intrinsecamente mais pesado que Kotlin puro: os 0,96 MB do poc-02 não são atingíveis. `≤ 20 MB por ABI` é o teto de "shippable" com split de ABI; o delta bruto vs 0,96 MB é registrado como dado. O veto de esforço transforma estouro de custo em resultado: se ambas as variantes estourarem, o relatório recomenda "não seguir por binding" e a change encerra por falha conclusiva — válido.

### D6 — Módulo `poc03/` isolado; `poc01/` e `poc02/` preservados como baselines

Mesma lógica do poc-01/02: código descartável (facade Go/Rust + cola KMP) fora de `shared`/`desktopApp`, trivial de apagar após o relatório. `poc01/` (nabu) e `poc02/` (própria) não são tocados — são as referências vivas para as comparações de APK/LoC/latência e a prova de que os números anteriores são reproduzíveis no mesmo dispositivo.

### D7 — Reuso do manifesto do poc-01; identidade Ed25519 inalterada

O manifesto assinado (Ed25519 + `seq`, serialização canônica) validado no E3 do poc-01 e reusado no poc-02 (D8 de ambos) é independente de stack de rede. O poc-03 o reutiliza sem revalidar: os blocos trafegam pelo Request-Response do libp2p, mas a verificação de assinatura/hash é o mesmo código Kotlin, do lado do app — a fronteira FFI entrega bytes, o Kotlin verifica. Isso mantém a verificação comparável entre as três POCs.

### D8 — Ordem: E1 primeiro (risco existencial), com veto; E2 → E3 → E4 → E5

O risco que pode matar a POC é o binding/cross-compile — por isso o E1 abre, com as duas variantes e o veto de esforço. Ao contrário do poc-02, não há experimento que possa correr em paralelo sem o binding: E2 (FFI/blocos) precisa do facade do E1; E3 (descoberta) roda sobre o nó embarcado; E4 (E2E) precisa de E2+E3; E5 (medições) fecha nas mesmas condições do poc-01/02. Se o E1 estourar o veto nas duas variantes, os experimentos dependentes não rodam e a POC encerra com resultado negativo conclusivo.

## Risks / Trade-offs

- [Cross-compile/FFI custar mais que qualquer POC anterior — o risco central] → veto a priori de 5 dias/variante no E1 (D5); estourar é dado, não fracasso; horas/dias reais registrados por experimento. As duas variantes correm de forma independente — a POC não fica refém de um toolchain só.
- [APK explodir com runtime Go/`.so` × ABIs] → limiar `≤ 20 MB por ABI` com split de ABI fixado a priori; delta bruto vs 0,96 MB do poc-02 registrado como o custo honesto do caminho de referência.
- [rust sem Bitswap quebrar a paridade go×rust] → blocos por Request-Response nos dois (D3); Bitswap fica isolado como bônus só-go de interop, fora do caminho comparável.
- [Bugs na fronteira FFI (threading, memória, lifecycle) — nova classe vs Kotlin puro] → superfície mínima (4 chamadas: dial/resolve/get-blocks/verify); a verificação crítica (assinatura/hash) fica em Kotlin, não cruza a fronteira (D7); classes de bug FFI registradas no E2.
- [Comparação enviesada — libp2p de referência é maduro, código novo é limpo] → mesmos limiares, mesmo dispositivo, mesmo roteiro e mesmo capítulo de teste; números de nabu/própria vêm dos relatórios publicados, não de re-medição seletiva.
- [Interop com Amino/kubo ser frágil/lento e travar a POC] → interop é bônus só-coletado (D4), nunca no caminho crítico do E4; o critério E2E fecha na rede-bootstrap própria.
- [PoC "vaza" para produto] → `poc03/` fora dos módulos de produto; o relatório, não o código, é o entregável.

## Migration Plan

Não aplicável — código descartável, sem deploy nem produto afetado. Fechamento = relatório escrito e roadmap/relatórios anteriores referenciando a change; o módulo `poc03/` pode ser arquivado/removido depois.

## Open Questions

- Qual toolchain de binding dá menos atrito no CI: `gomobile bind` (go) e `cargo-ndk`+UniFFI (rust) para todas as ABIs alvo? (primeira tarefa do E1)
- O facade deve expor um nó libp2p opaco com 4 chamadas, ou também um handle de conexão para reconexão/streaming? (decidir no E2, medir o impacto na superfície FFI)
- QUIC 0-RTT de fato encurta a reconexão abaixo do handshake completo do Noise do poc-02, no RTT de rede móvel? (medir no E5)
- O go via `boxo` consegue interop real com um kubo local (want/have de bloco), fechando o gap que o poc-01 não fechou? (bônus do E3/E5, registrar)
- Vale um alvo iOS mínimo para validar a promessa KMP do UniFFI, ou fica fora do escopo desta POC? (decidir no E1; provavelmente fora, registrar)
