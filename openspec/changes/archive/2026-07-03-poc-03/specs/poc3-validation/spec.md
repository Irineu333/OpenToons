## ADDED Requirements

### Requirement: E1 — Binding do libp2p de referência em duas variantes comparadas
A PoC SHALL embarcar o libp2p de referência no Android e no KMP em duas variantes, via gerador de binding pronto mais um facade fino próprio (Tier B do design D1): **E1a — go-libp2p** via `gomobile bind` (`.aar` + `c-shared` para desktop) e **E1b — rust-libp2p** via UniFFI + `cargo-ndk` (Kotlin + `.so`). Cada variante SHALL respeitar o veto de esforço de 5 dias úteis (design D5) para chegar ao E2E no dispositivo; estourar o veto encerra a variante com resultado registrado. A comparação SHALL registrar, por variante: dias de esforço, LoC do facade, complexidade de build (ABIs cross-compiladas), tamanho do binário adicionado por ABI, e ajuste ao KMP.

#### Scenario: Facade cross-compilado e carregado no Android
- **WHEN** o facade de uma variante é cross-compilado para as ABIs alvo (arm64-v8a, armeabi-v7a, x86_64) e o app o carrega no dispositivo físico
- **THEN** o nó libp2p inicializa dentro do app sem crash e expõe a superfície mínima (`dial`/`resolve`/`get-blocks`/`verify`) através da fronteira FFI

#### Scenario: Superfície idêntica nas duas variantes
- **WHEN** as variantes E1a e E1b concluem o binding
- **THEN** ambas expõem a mesma superfície FFI mínima ao Kotlin, permitindo que o E2/E3/E4 rodem sobre qualquer uma sem mudar o código do app

#### Scenario: Veto de esforço por variante é resultado válido
- **WHEN** uma variante estoura o veto de 5 dias úteis sem chegar ao E2E no dispositivo
- **THEN** o relatório registra a evidência e a variante encerra; se ambas estourarem, a PoC recomenda não seguir por binding e é considerada completa sem os experimentos dependentes

#### Scenario: Prontos de nível-alto registrados, não adotados
- **WHEN** a decisão de usar Tier B (facade próprio) é tomada
- **THEN** o relatório registra os prontos Tier A avaliados (gomobile-ipfs, iroh-ffi) e o motivo de não os adotar (peso/escopo), sem investir neles

### Requirement: E2 — Superfície FFI mínima e troca de blocos por Request-Response
A PoC SHALL demonstrar uma superfície FFI mínima (`dial`, `resolve`, `get-blocks`, `verify`) cruzando a fronteira nativo↔Kotlin, com a troca de blocos por **Request-Response** (nativo em go e rust) para manter paridade entre as variantes e reusar o formato de manifesto validado no poc-01. A verificação de assinatura/hash SHALL ocorrer em Kotlin, do lado do app, sem cruzar a fronteira FFI. O throughput SHALL ser comparado ao do nabu (poc-01) e da stack própria (poc-02) no mesmo capítulo de teste.

#### Scenario: Download e verificação de capítulo via Request-Response
- **WHEN** o app requisita o manifesto e os blocos de um capítulo publicado num nó, através do facade
- **THEN** ele baixa manifesto + blocos por Request-Response, verifica em Kotlin a assinatura Ed25519 do manifesto e o hash de cada bloco, e reconstrói o capítulo íntegro

#### Scenario: Paridade go × rust na troca de blocos
- **WHEN** o mesmo capítulo é baixado por E1a (go) e por E1b (rust)
- **THEN** ambas usam Request-Response com o mesmo formato de bloco, produzindo resultados comparáveis registrados lado a lado no relatório

#### Scenario: Classes de bug FFI registradas
- **WHEN** a superfície FFI é exercitada sob concorrência e ciclos de vida do app
- **THEN** quaisquer problemas de threading, memória ou lifecycle na fronteira são registrados como dado do custo do caminho de binding

#### Scenario: Bitswap como bônus só-go de interop
- **WHEN** a variante go habilita Bitswap via `boxo` contra um kubo local
- **THEN** o resultado da interop (want/have de bloco) é registrado como bônus; a variante rust não é penalizada por não ter Bitswap oficial

### Requirement: E3 — Descoberta com Kademlia real na rede-bootstrap própria
A PoC SHALL usar o Kademlia de referência (real, não simulado) para descoberta fria na rede-bootstrap própria da OpenToons (nós plenos com endereço público, topologia do E5 do poc-01). As métricas SHALL incluir RTTs por lookup do cliente, tempo de convergência pós-churn e tráfego. A descoberta na Amino SHALL ser apenas uma tentativa registrada (dado histórico vs poc-01), não critério de conclusão.

#### Scenario: Descoberta fria na rede própria
- **WHEN** o cliente conhece apenas o nó de bootstrap e um obraId publicado por outro nó pleno
- **THEN** ele resolve o provider correto via Kademlia e o número de RTTs é medido contra o limiar de ≤ 3 RTTs

#### Scenario: Cliente não serve nem roteia
- **WHEN** o cliente executa lookups Kademlia
- **THEN** ele opera em modo client puro — não armazena registros de terceiros, não roteia e não aceita conexões de entrada, conforme ADR-0005

#### Scenario: Tentativa na Amino registrada
- **WHEN** o nó de referência tenta conectar e resolver na Amino
- **THEN** o resultado (conectou? resolveu?) é registrado no relatório como dado comparativo ao poc-01, sem ser critério de conclusão da PoC

### Requirement: E4 — Ciclo E2E do Marco 0 com o libp2p de referência
A PoC SHALL demonstrar, com o libp2p de referência (binding do E1 + FFI do E2 + Kademlia do E3), o mesmo critério de conclusão do Marco 0 fechado pelo nabu (poc-01) e pela stack própria (poc-02): um capítulo assinado publicado num nó com endereço público manual é descoberto por descoberta fria e baixado por um mobile em outra rede, atrás de NAT, com verificação de assinatura.

#### Scenario: Descoberta fria, download e verificação pelo caminho público real
- **WHEN** o app no dispositivo físico, em outra rede e conhecendo somente o endereço do bootstrap e o obraId, busca o capítulo de teste
- **THEN** ele descobre o nó publicador (nunca informado), disca seu endereço público, baixa manifesto + blocos, verifica a assinatura e reconstrói o capítulo íntegro

#### Scenario: Conteúdo adulterado é rejeitado
- **WHEN** um bloco baixado não corresponde ao hash do manifesto, ou o manifesto falha na verificação de assinatura
- **THEN** o app rejeita o capítulo e reporta a falha de verificação

### Requirement: E5 — Medições comparativas e dados só-coletados
A PoC SHALL repetir a sessão de medição do poc-01/02 (30 minutos, lookups periódicos, mesmo dispositivo Moto g(30), mesmas ferramentas) com o libp2p de referência, registrando as métricas contra os limiares do design D5, lado a lado com nabu (poc-01) e stack própria (poc-02). A PoC SHALL também coletar os dados que nenhuma POC anterior colheu: hole-punch DCUtR (TCP), estabilidade de QUIC em dials paralelos e interop com kubo (bônus só-go) — sem limiar, o objetivo é registrar.

#### Scenario: Sessão de 30 minutos nas mesmas condições
- **WHEN** a sessão de leitura com lookups periódicos é executada no mesmo dispositivo e roteiro do poc-01/02
- **THEN** bateria (< 5%) e dados além do conteúdo (< 20 MB) são medidos e comparados aos limiares e aos valores de nabu (≈ 0,03% / 1,09 MB) e própria (≈ 0,012% / 0,13 MB)

#### Scenario: Tamanho de APK por ABI registrado
- **WHEN** o app completo com o binding é empacotado com split de ABI
- **THEN** o tamanho por ABI é medido contra o limiar de ≤ 20 MB por ABI e o delta bruto vs 0,96 MB do poc-02 é registrado como o custo do caminho de referência

#### Scenario: Latências de conexão medidas
- **WHEN** o app realiza a primeira conexão e uma reconexão a um nó em rede real
- **THEN** handshake (< 1 s) e reconexão (< 500 ms, incluindo QUIC 0-RTT se houver) são medidos contra os limiares e comparados à stack própria

#### Scenario: Dados só-coletados de NAT, QUIC e interop registrados
- **WHEN** os cenários de hole-punch DCUtR (device↔device sem port-forward, TCP), dials QUIC paralelos e interop com kubo local são executados
- **THEN** os resultados são registrados no relatório como dado para decisões futuras (NAT do marco 4, viabilidade de QUIC, interop com IPFS), sem constituírem critério de aprovação/reprovação da PoC
