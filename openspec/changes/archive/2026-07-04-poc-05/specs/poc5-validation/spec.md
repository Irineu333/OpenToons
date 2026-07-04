## ADDED Requirements

### Requirement: E0 — Definição de "anônimo" a priori + seam estendido com push + TCK
A PoC SHALL fixar, ANTES de qualquer experimento, a definição de "anônimo": o modo protege o vínculo entre a identidade Ed25519 do publicador (pública por design) e o IP/localização do desktop (pseudonimato com privacidade de rede), e NÃO promete anonimato absoluto, ocultação da VPS (pública por P4), nem resistência a análise de timing/adversário global. A PoC SHALL estender o seam do poc-04 (`poc05/api`, Kotlin commonMain) com um RPC de replicação por empurrão `push(provider, manifest, blocks)` — a peça que o publicador não-discável força — mantendo tipos 100% neutros, `verify` fora do seam, e SHALL escrever o TCK do push antes dos adapters. O frame de push SHALL NOT carregar endereço de origem do publicador.

#### Scenario: Definição de anônimo e limites registrados antes de medir
- **WHEN** o E0 é concluído
- **THEN** o que o modo protege e o que explicitamente não promete estão escritos e commitados antes de qualquer captura ou medição

#### Scenario: push cabe no seam neutro
- **WHEN** o método `push` é revisado contra os dois backends planejados
- **THEN** nenhum parâmetro expõe conceito específico de backend nem endereço de origem; o publicador entrega conteúdo e o receptor grava no `Blockstore` via a mesma interface

#### Scenario: TCK do push aceita autêntico e rejeita chave errada
- **WHEN** o TCK exercita push de um publicador autenticado pelo Noise e push com manifesto assinado por chave errada
- **THEN** o primeiro é gravado no blockstore e o segundo é rejeitado ANTES de gravar (assinatura validada na recepção), com os mesmos vetores para qualquer backend

### Requirement: E1 — Modo anônimo no backend Trama (SOCKS5h, sem vazar DNS)
A PoC SHALL implementar o dial da Trama através de um proxy SOCKS5 (`java.net.Proxy`) com resolução remota (SOCKS5h) ou dial por onion, de modo que nenhuma resolução de nome ocorra localmente. O handshake Noise XX SHALL autenticar a identidade Ed25519 do par através do túnel, rejeitando impostor antes de qualquer dado (defesa contra exit malicioso). O RPC de push SHALL trafegar sobre o canal Noise tunelado.

#### Scenario: Dial tunelado sem resolução local de DNS
- **WHEN** o adapter Trama disca o replicador via SOCKS5/onion com captura de rede ativa
- **THEN** nenhum pacote DNS sai da máquina do publicador; a resolução (quando existe) acontece dentro do circuito Tor

#### Scenario: Noise autentica o par através do túnel
- **WHEN** um servidor com identidade Ed25519 diferente da esperada responde através do exit
- **THEN** o handshake é rejeitado antes de qualquer dado de aplicação, como em clearnet (poc-02)

### Requirement: E2 — Modo anônimo no backend rust-libp2p (QUIC off, swarm contido)
A PoC SHALL estender o facade rust do poc-04 com transporte Tor (SOCKS/onion) para TCP, com QUIC desligado (UDP não trafega no Tor) e todo comportamento que disca automaticamente (identify anunciando endereços observados, mDNS, multiaddrs `/dns/`) contido ou desligado. O adapter SHALL implementar o push como protocolo request-response. O adapter SHALL respeitar o veto de esforço de 5 dias úteis; estourar o veto (Transport SOCKS custoso) OU vazar no pcap sem contenção viável encerra a célula com resultado registrado.

#### Scenario: Swarm não vaza fora do circuito
- **WHEN** o full node/cliente rust-libp2p opera em modo anônimo com captura de rede ativa
- **THEN** nenhum pacote sai para destino diferente do daemon Tor local — nenhum dial direto de identify/mDNS/DNS

#### Scenario: Inviabilidade no backend é resultado válido
- **WHEN** o Transport Tor do rust-libp2p estoura o veto de esforço ou o swarm vaza no pcap sem contenção viável
- **THEN** a célula libp2p fecha negativa com evidência e o relatório registra "modo anônimo commita na Trama" — resultado conclusivo, como o go-libp2p saiu do poc-03

### Requirement: E3 — Descoberta do replicador através do Tor (cenário 2)
A PoC SHALL provar que o publicador, conhecendo somente o bootstrap (e a pubkey dele), descobre o replicador — nunca informado — por dentro do túnel Tor, nos dois backends. A PoC SHALL medir a divergência: PEX/RESOLVE da Trama (um circuito) contra o walk de Kademlia do rust-libp2p (dials a múltiplos peers, múltiplos circuitos), registrando a latência de lookup frio de cada um.

#### Scenario: Publicador descobre replicador nunca informado via Tor
- **WHEN** o publicador em modo anônimo conhece só o endereço do bootstrap e resolve a localização do replicador
- **THEN** o replicador (endereço nunca fornecido ao publicador) é descoberto por dentro do circuito e discado, sem que o publicador vaze o próprio IP

#### Scenario: Divergência de descoberta entre backends medida
- **WHEN** o lookup frio via Tor roda no backend Trama e no rust-libp2p
- **THEN** a latência de cada mecanismo (1 circuito × walk multi-circuito) é registrada contra o limiar de < 10 s refixado para Tor

### Requirement: E4 — Matriz E2E 2×2 real (IP conhecido × descoberta via Tor), sem falseamento
A PoC SHALL fechar a matriz de 2 cenários × 2 backends (4 células) com o mesmo app leitor e zero ramificação por backend: topologia com publicador P sobre Tor (0 sockets de escuta, só saída), replicador R na clearnet com IP público, bootstrap B na clearnet, e leitor M no Moto g(30) atrás de NAT em dados móveis. C1 — push P→R com o IP de R conhecido pelo publicador, seguido de fetch verificado em M. C2 — push P→R com R descoberto via Tor (P conhece só B), seguido de fetch em M. Simulação in-process SHALL NOT contar como célula verde; o app leitor SHALL NOT ter nenhuma linha alterada entre backends.

#### Scenario: C1 — push com IP conhecido e fetch no mobile
- **WHEN** o publicador anônimo empurra um capítulo real para o replicador (IP conhecido) e o app no dispositivo em dados móveis descobre e baixa do replicador pela clearnet
- **THEN** a assinatura Ed25519 e os hashes verificam, o capítulo é reconstruído, e as variantes adulteradas são rejeitadas — no backend Trama e no rust-libp2p (ou célula ❌ registrada)

#### Scenario: C2 — push com descoberta via Tor e fetch no mobile
- **WHEN** o publicador anônimo, conhecendo só o bootstrap, descobre o replicador via Tor, empurra o capítulo, e o app baixa do replicador pela clearnet
- **THEN** o ciclo completo fecha com verificação e rejeições, sem que o publicador conheça previamente nem vaze o IP do replicador ou o próprio

#### Scenario: App leitor idêntico entre backends
- **WHEN** o código do app leitor é inspecionado entre as build variants trama e libp2p
- **THEN** não existe nenhum branch por backend; o anonimato do publicador é invisível a quem consome (o mobile lê da clearnet como sempre)

### Requirement: E4-T — Critério transversal: não-vazamento do IP do publicador (auditado)
Em TODAS as células da matriz, a PoC SHALL provar a ausência de vazamento do IP do publicador por auditoria, não por declaração, em quatro camadas: (1) captura de rede na interface de P mostrando zero pacotes para qualquer destino diferente do daemon Tor local, incluindo DNS; (2) zero sockets de escuta não-loopback em P durante a sessão; (3) todos os IPs vistos por B e R nas conexões de P diferentes do IP real de P e pertencentes à lista de exit nodes do consenso Tor (ou, no caminho onion, sem IP de origem discável registrado); (4) dump do wire do push sem nenhuma ocorrência de endereço de P. Uma célula que funcione mas vaze em qualquer camada SHALL ser considerada negativa.

#### Scenario: pcap prova zero vazamento
- **WHEN** a sessão completa de uma célula é capturada na interface do publicador
- **THEN** não há nenhum pacote para destino diferente do daemon Tor local (inclui DNS/NTP/dial direto) — critério binário

#### Scenario: IPs observados são exits conhecidos
- **WHEN** os logs de conexão de entrada de B e R são cruzados com o consenso Tor
- **THEN** todo IP atribuível ao publicador é distinto do IP real dele e é um exit node do consenso (ou a conexão chegou pelo onion service sem IP de origem discável)

#### Scenario: Funciona mas vaza é célula negativa
- **WHEN** uma célula completa o E2E mas a captura mostra ao menos um pacote fora do circuito Tor
- **THEN** a célula é marcada negativa e o relatório registra a origem do vazamento — funcionalidade não compra a asserção de anonimato

### Requirement: E5 — Custo da abstração, robustez de circuito e latências
A PoC SHALL medir o custo incremental do modo anônimo: o inventário de pontos de vazamento novos do seam (meta ≤ 1 além dos 3 do poc-04, expressos como capability-flag documentada — ex.: `ANONYMOUS_DIAL`), a regressão de peso, a LoC de cola por adapter, e o esforço por adapter contra o veto. A PoC SHALL provar a robustez de circuito (morte de circuito no meio do push retoma sem intervenção) e registrar as latências degradadas contra os limiares refixados para Tor (handshake < 10 s; requisição quente < 2 s; push 768 KiB < 60 s; lookup frio < 10 s).

#### Scenario: Config de anonimato não vira branch de app
- **WHEN** o inventário de vazamento do `:api` é auditado por grep
- **THEN** o modo anônimo adiciona no máximo uma capability-flag documentada e zero branch por backend no código do app

#### Scenario: Push sobrevive à troca de circuito
- **WHEN** um circuito Tor é morto durante o push de um capítulo de 768 KiB
- **THEN** a transferência retoma e completa sem intervenção manual, com verificação da assinatura ao final

#### Scenario: Latências degradadas dentro dos limiares refixados
- **WHEN** handshake, requisição quente, push e lookup frio são medidos sobre o circuito Tor
- **THEN** cada métrica fica dentro do limiar refixado a priori para Tor, ou o excesso é registrado como dado honesto sobre a UX do modo anônimo

### Requirement: E6 — Relatório com veredito Q1–Q10
A PoC SHALL produzir `docs/poc05-report.md` respondendo as questões Q1–Q10 fixadas a priori e terminando com um veredito explícito: modo anônimo viável e abaixo do seam / viável mas vaza no seam / inviável num dos backends — e se constitui um gatilho invertido (requisito de anonimato como argumento contra migrar da Trama para o rust-libp2p). Cada claim SHALL ser etiquetada com sua classe de evidência: executado, dado-só ou só-design.

#### Scenario: Todas as questões respondidas com evidência etiquetada
- **WHEN** o relatório é concluído
- **THEN** Q1–Q10 têm resposta com classe de evidência explícita e o veredito de estratégia está declarado

#### Scenario: Gatilho invertido avaliado
- **WHEN** a comparação entre os dois backends no eixo anônimo é sintetizada
- **THEN** o relatório declara se o modo anônimo favorece a Trama e o que isso implica para os gatilhos de migração da Q10 do poc-04
