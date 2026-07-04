## ADDED Requirements

### Requirement: E0 — Interface neutra e suíte de conformidade (TCK) antes de qualquer adapter
A PoC SHALL definir em `poc04/api` (Kotlin commonMain) as interfaces `P2pBackend` (client: `dial`/`resolve`/`getBlocks`/`close`) e `FullNode` (servidor: `start`/`publish`/`announce`/`serve`/`stop`) com tipos 100% neutros (`ObraId`, `ContentId` sha256, `Provider`, `BootstrapAddr`, `Block`, `Manifest`) — nenhum tipo ou conceito específico de backend na superfície. A verificação (`verify`: Ed25519 do manifesto + hash por bloco) SHALL viver no `api`, em Kotlin, fora do seam. A suíte de conformidade (TCK) SHALL ser escrita antes de qualquer adapter e rodar idêntica em qualquer backend.

#### Scenario: Interface sem conceitos de backend
- **WHEN** as interfaces e tipos do `poc04/api` são revisados contra os dois backends planejados (Trama e rust-libp2p)
- **THEN** nenhum método, tipo ou parâmetro expõe conceito exclusivo de um backend (CID, multiaddr, PeerId, digest de gossip); conversões vivem dentro dos adapters

#### Scenario: TCK definido antes dos adapters
- **WHEN** o TCK é concluído
- **THEN** ele cobre resolve, download, verificação, rejeição de adulterado e expiry/republish com os mesmos vetores para qualquer implementação, e nenhum adapter existia quando os cenários foram fixados

#### Scenario: Divergência de capacidade vira capability-flag
- **WHEN** uma capacidade existe em um backend e não no outro (ex.: hole-punch)
- **THEN** ela é exposta como capability-flag consultável documentada — nunca como método específico — e conta no inventário de vazamento do E5

### Requirement: E1 — Paridade de client pela interface comum
A PoC SHALL implementar os dois backends de client atrás da mesma interface `P2pBackend` — Trama (stack do poc-02) e rust-libp2p (facade do poc-03) — e ambos SHALL passar o mesmo TCK de client com 100% dos cenários verdes, sem nenhuma ramificação por backend no código que consome a interface.

#### Scenario: Mesmo TCK verde nos dois backends
- **WHEN** o TCK de client roda contra o adapter Trama e contra o adapter rust-libp2p
- **THEN** os dois passam 100% dos cenários, com os mesmos vetores e as mesmas asserções

#### Scenario: Zero ramificação por backend no consumidor
- **WHEN** o código do app/TCK que consome `P2pBackend` é inspecionado
- **THEN** não existe nenhum branch condicionado ao tipo de backend (`if backend is …`); a escolha vive somente no grafo de dependências do build

### Requirement: E2 — Seam de full-node unifica gossip e Kademlia (a questão central Q1)
A PoC SHALL implementar o lado full-node dos dois backends atrás da mesma interface `FullNode`, com implementação real dos dois lados: o nó Trama (membership/gossip do poc-02 refatorado para trás do seam) e o nó rust-libp2p (facade do poc-03 estendido via FFI com listen, Kademlia server, provide e serve-blocks, incluindo build de host para desktop JVM). A semântica de expiry/republish SHALL ficar interna ao backend. Cada adapter SHALL respeitar o veto de esforço de 5 dias úteis; estourar o veto encerra o adapter com resultado registrado.

#### Scenario: Mesma interface publica e anuncia nos dois backends
- **WHEN** um full node de cada backend executa `publish` + `announce` de uma obra de teste pela interface comum
- **THEN** um client do mesmo backend resolve o provider e baixa o conteúdo, sem que a interface tenha exigido método ou parâmetro específico de backend

#### Scenario: Lado servidor rust-libp2p dirigido pela interface Kotlin
- **WHEN** o full node rust-libp2p roda em desktop JVM via build de host do facade
- **THEN** listen, provide e serve-blocks são acionados exclusivamente pela interface `FullNode` em Kotlin — não por binário avulso fora do seam — e classes de bug FFI do host são registradas

#### Scenario: Seam não unifica é resultado válido
- **WHEN** `announce`/`republish`/`serve` de um backend não consegue ser expresso pela interface comum sem método específico, ou o veto de esforço estoura
- **THEN** o relatório registra a evidência, a Q1 é respondida negativamente e a PoC encerra com veredito "commit num backend" — resultado negativo conclusivo

### Requirement: E3 — Features no tempo: abaixo do seam ou vazamento
A PoC SHALL classificar cada feature de médio/longo prazo em: abaixo do seam (ganha-se trocando de backend com 0 linha de app alterada) ou vazamento (exige mudança no app/interface). As features avaliadas SHALL incluir: hole-punch DCUtR/relay (executar se o setup couber; senão dado-só com componentes wired), migração de descoberta gossip→DHT (o app percebe?), e transporte browser/WebRTC (sonda só-design). Cada claim SHALL ser etiquetada com sua classe de evidência: executado, dado-só ou só-design.

#### Scenario: Feature exclusiva capturada por swap de backend
- **WHEN** uma feature que só o rust-libp2p possui é habilitada trocando o backend do build
- **THEN** nenhuma linha do código do app muda; a feature é classificada "abaixo do seam" com evidência etiquetada

#### Scenario: Migração de descoberta invisível ao app
- **WHEN** o mesmo app resolve uma obra via backend Trama (gossip) e via backend rust-libp2p (Kademlia)
- **THEN** `resolve(obraId)` esconde o mecanismo de descoberta e o app não percebe a diferença além de latência

#### Scenario: Custo do "construir do zero" dimensionado
- **WHEN** o relatório compara o que o rust-libp2p entrega pronto contra o que a Trama teria que construir (hole-punch coordenado, DHT de produção, transportes)
- **THEN** cada item recebe uma estimativa de esforço fundamentada e a classe de evidência correspondente, respondendo a Q7

### Requirement: E4 — Matriz E2E 4×2 real, sem falseamento
A PoC SHALL fechar a matriz de 4 cenários × 2 backends (8 células) com o mesmo app, zero ramificação por backend, e implementação real dos dois lados: (S1) malha de 4 nós plenos em processos separados, incluindo morte do publicador com expiry e republish; (S2) full node servindo capítulo real ao dispositivo físico com verificação e rejeição de adulterado; (S3) descoberta transitiva — o app conhece somente o bootstrap e descobre o publicador nunca informado; (S4) internet real — dispositivo em dados móveis (rede separada) alcançando full nodes por endereço público. Simulação in-process e servidor fora da interface SHALL NOT contar como célula verde; `adb reverse` vale apenas para smoke test de desenvolvimento.

#### Scenario: S1 — malha multi-nó converge e sobrevive à morte do publicador
- **WHEN** 4 full nodes de um backend (bootstrap, publicador, 2 nós de sustentação) rodam em processos separados e o publicador é morto e revivido
- **THEN** a malha converge, o anúncio da obra se propaga, o provider expira após a morte e reaparece com o republish — no backend Trama e no rust-libp2p

#### Scenario: S2 — transferência real com verificação e rejeição
- **WHEN** o app no dispositivo físico baixa um capítulo real (manifesto Ed25519 + blocos) de um full node de cada backend
- **THEN** a assinatura e os hashes verificam, o capítulo é reconstruído, e as variantes adulteradas (bloco corrompido; manifesto de chave errada) são rejeitadas

#### Scenario: S3 — descoberta transitiva de publicador nunca informado
- **WHEN** o app conhece somente o endereço do bootstrap e o obraId
- **THEN** ele descobre o publicador via bootstrap, disca-o e baixa o conteúdo, e o publicador confirma por log a conexão vinda do app — nos dois backends

#### Scenario: S4 — critério do Marco 0 pela internet real, nos dois backends
- **WHEN** o app no dispositivo físico em rede separada (dados móveis/hotspot de outra operadora) executa o ciclo completo contra full nodes em endereço público
- **THEN** descoberta fria, download, verificação e rejeição de adulterado fecham pelo caminho público real com o mesmo app, trocando apenas o backend do build

#### Scenario: Célula verde exige realidade
- **WHEN** uma célula da matriz só passa com simulação in-process, servidor fora da interface, `adb reverse` ou ramificação por backend no app
- **THEN** a célula é registrada como falha e o vazamento-sob-realidade é documentado como evidência contra a Q1

### Requirement: E5 — Ponte de migração dual-stack e custo da abstração
A PoC SHALL provar a ponte de migração: um full node dual-stack (os dois backends sobre o mesmo blockstore, em host onde peso é livre) servindo o mesmo capítulo a um cliente-Trama e a um cliente-rust-libp2p, ambos com bytes idênticos e assinatura verificada. A PoC SHALL medir o custo da abstração contra os limiares a priori do design D5: regressão de peso por backend (≤ +5% vs 0,96 MB do poc-02 e vs 8–11 MB/ABI do poc-03, um backend por build mobile), LoC de cola por adapter (≤ ~150), e o inventário exaustivo de pontos de vazamento (≤ 3, todos capability-flag documentada).

#### Scenario: Dual-stack serve os dois clientes sem flag day
- **WHEN** o full node dual-stack hospeda uma obra e um cliente de cada backend a baixa
- **THEN** os dois clientes recebem bytes idênticos com assinatura verificada, provando que a migração `própria → libp2p` é factível por transição (full nodes dual-stack primeiro, clientes por update) sem flag day

#### Scenario: Mobile nunca carrega os dois backends
- **WHEN** os APKs das duas build variants são inspecionados
- **THEN** cada APK contém exatamente um backend, com regressão de peso ≤ +5% sobre a baseline publicada do respectivo backend

#### Scenario: Inventário de vazamento fecha a Q2
- **WHEN** o inventário exaustivo de pontos onde conhecimento de backend escapa do `api` é compilado
- **THEN** o total, a natureza (capability-flag, config, semântica documentada) e a localização de cada ponto são publicados; ≤ 3 pontos todos capability-flag = seam saudável; acima disso a abstração é reprovada

### Requirement: Relatório final com Q1–Q12 respondidas e veredito de estratégia
A PoC SHALL produzir `docs/poc04-report.md` como artefato durável, respondendo uma a uma as questões Q1–Q12 fixadas a priori no design, com os limiares definidos antes de qualquer medição e o esforço real registrado por experimento. Diferente do poc-03, o relatório SHALL terminar com recomendação de estratégia: `própria`, `rust-libp2p` ou `própria → rust-libp2p condicional`, incluindo o gatilho concreto e observável para percorrer a migração (Q10) e o julgamento honesto da Q12 (o imposto da opcionalidade vale, ou "commit na própria e assumir o acoplamento" é mais honesto?).

#### Scenario: Todas as questões respondidas com evidência
- **WHEN** o relatório é concluído
- **THEN** cada questão Q1–Q12 tem resposta explícita ancorada no experimento correspondente, com classe de evidência etiquetada (executado, dado-só, só-design) onde aplicável

#### Scenario: Veredito de estratégia com gatilho
- **WHEN** as questões pivotais (Q1 seam, Q2 vazamento, Q6/Q7 valor da opção, Q9 ponte) estão respondidas
- **THEN** o relatório declara a estratégia recomendada para o Marco 2 e, se for `própria → rust-libp2p condicional`, define o gatilho observável em produção (ex.: demanda real de hole-punch, escala > 5.000 registros, leitor web)

#### Scenario: Resultado negativo é conclusivo
- **WHEN** a Q1 falha (seam não unifica) ou o inventário de vazamento estoura o limiar
- **THEN** o relatório recomenda commit num único backend com a evidência do porquê, e a PoC é considerada completa e bem-sucedida como experimento
