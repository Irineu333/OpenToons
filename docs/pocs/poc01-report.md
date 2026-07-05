# Relatório da PoC — Marco 0 (poc-01)

> Artefato durável do Marco 0. O código em `poc01/` é descartável; o que vale é este relatório.
> Change: [openspec/changes/archive/2026-07-03-poc-01](../../openspec/changes/archive/2026-07-03-poc-01/proposal.md) · Design: [design.md](../../openspec/changes/archive/2026-07-03-poc-01/design.md)

**Status: CONCLUÍDO** (jul/2026). Todos os experimentos executados, incluindo dispositivo físico, medição de dados por UID, o E2E pelo endereço público a partir de outra rede (critério do Marco 0) e o **E5: rede bootstrap/DHT própria com descoberta fria — validada** (e reconfirmada numa VPS pública real e separada; ver E5). A descoberta na Amino em escala permanece bloqueada por bugs upstream do nabu/jvm-libp2p (todos diagnosticados, com workarounds; ver E2/4.1 e E5). **Recomendação: prosseguir para o Marco 1/2 com a stack nabu sobre rede própria da OpenToons.**

> **Follow-up (poc-02):** a recomendação de rede própria motivou a hipótese inversa — uma
> camada de rede **implementada do zero, sem framework P2P**, comparada lado a lado com esta
> stack. Ver [openspec/changes/archive/2026-07-03-poc-02](../../openspec/changes/archive/2026-07-03-poc-02/proposal.md) e
> [poc02-report.md](./poc02-report.md).
>
> **Follow-up (poc-03):** este relatório testou o libp2p só pela via JVM capenga (nabu). O
> [poc-03](../../openspec/changes/archive/2026-07-03-poc-03/proposal.md) fecha o buraco medindo o libp2p **de
> referência** (go-libp2p e rust-libp2p) via bindings nativos (gomobile; UniFFI). E1 conclusivo:
> as duas variantes cross-compilam e **rodam no mesmo Moto g(30)** — o "plano B (gomobile/UniFFI)"
> que este relatório declarou nunca ter precisado é viável. Ver [poc03-report.md](./poc03-report.md).

## Limiares fixados a priori (design D5)

Definidos ANTES de qualquer medição. Ajustes posteriores exigem justificativa registrada aqui.

| Métrica | Cenário | Limiar |
|---|---|---|
| Dados móveis | Sessão de 30 min, tráfego do UID do app **além do conteúdo baixado** | **< 20 MB** |

Ferramentas: contadores de tráfego por UID (`dumpsys netstats` ou TrafficStats). **Energia
(bateria) não é reportada:** a medição só é possível com o aparelho desplugado, e o rig usa
USB para o controle adb — o `batterystats` não estima drain sob carga. O custo do DHT client
é o tráfego por UID (medido, abaixo).

## Questões abertas a responder (do design)

| # | Questão | Resposta |
|---|---|---|
| Q1 | O nabu expõe API para atuar como *DHT client puro* (sem aceitar entrada, sem servir DHT), ou exige configuração/patch? | **Não há flag pronta; é alcançável com um binding customizado de ~15 linhas, sem fork.** `EmbeddedIpfs.build()` sempre registra o `Kademlia` como binding completo (o `KademliaProtocol.onStartResponder` serve requisições DHT de terceiros); o booleano `localOnly` só alterna o protocol ID WAN/LAN. Tentativa 1 (não registrar o binding e usar só `dial()`) **falha**: o `HostImpl.newStream` do jvm-libp2p lança `NoSuchLocalProtocolException` — o protocolo precisa estar registrado localmente até para stream de saída (comprovado no emulador). Solução validada (`poc01/android/ClientModeKademlia.kt`): registrar um `StrictProtocolBinding` do `/ipfs/kad/1.0.0` que herda o iniciador real do `KademliaProtocol` mas cujo `onStartResponder` fecha o stream — nunca serve DHT. Com isso, 4/4 nós de bootstrap da Amino conectaram a partir do Android. Ressalva: o identify ainda anuncia `/ipfs/kad/1.0.0` (um client "de verdade" não anunciaria); registrar como melhoria upstream/patch para o Marco 2. |
| Q2 | Licenciamento do nabu + dependências embarcadas — aceitável para o produto? | **Sim, tudo permissivo:** nabu MIT (confirmado no repositório); jvm-libp2p Apache-2.0; Netty Apache-2.0; BouncyCastle licença MIT-like; noise-java (tech.pegasys) Apache-2.0; dnsjava BSD. Nenhuma copyleft forte. Conferir a lista transitiva completa no empacotamento do Marco 2. |
| Q3 | Tamanho do APK com nabu + Netty embarcados — aceitável? | **Parcial:** APK debug do spike (nabu completo + Netty, sem minify/R8) = **12 MB**. Com R8 tende a cair bastante; medir no APK release quando houver app real. Nenhum bloqueio evidente. |
| Q4 | Qual versão mínima de Android (minSdk) o stack suporta na prática? | Compila e dexa com **minSdk 26** sem core library desugaring (nabu = bytecode Java 11). Execução comprovada em **API 31** (Moto g(30), Android 12, físico) e API 37 (emulador). Abaixo de 26 não foi testado. |

## Versões usadas

| Componente | Versão | Fonte |
|---|---|---|
| nabu (poc01/node e poc01/android) | v0.8.0 + workarounds da PoC | JitPack (`com.github.Peergos:nabu`) — última tag com build OK lá (0.8.1 e 0.9.x-quic com erro de build). O commit `0b421b9427` (pós-QUIC, build OK no JitPack) foi usado só no diagnóstico de transporte e descartado por regressão no GET_PROVIDERS (ver Desvios) |
| jvm-libp2p (transitivo via nabu) | 0.18.0-ipv6-mdns-wildcard (fork `com.github.peergos`) | JitPack |
| noise-java (transitivo) | tech.pegasys:noise-java 22.1.0 | repo Maven da Consensys |
| Kotlin / Gradle | 2.4.0 / 9.1.0 | — |
| AGP (poc01/android) | 9.0.1 (Kotlin embutido; série mais nova compatível com Gradle 9.1) | Google Maven |
| Emulador do spike | AVD Galaxy_A54, Android 17 (API 37) | — |
| Dispositivo físico | Moto g(30), Android 12 (API 31) | — |
| JVM (poc01/node) | toolchain 21 | — |

## Experimentos

### E2 — Spike: nabu no Android (caminho crítico)

**Veredito: POSITIVO (confirmado em dispositivo físico).** O nabu compila, dexa e executa no Android — Moto g(30)/Android 12 e emulador API 37; em modo DHT client puro conectou a 4/4 nós de bootstrap da Amino em ambos. Nenhum plano B (gomobile/UniFFI) necessário. Ressalvas registradas: binding client customizado necessário (Q1), dnsaddr quebrado no Android (tarefa 2.3), identify ainda anuncia o protocolo kad.

- Modo DHT client puro (tarefa 2.1): **respondido — ver Q1**. Sem flag pronta; exige binding customizado (iniciador real + responder que recusa entrada), pois o jvm-libp2p requer o protocolo registrado localmente até para dial de saída. Para o download de blocos, o binding Bitswap precisa estar registrado para receber os blocos em streams de entrada **sobre a conexão de saída já aberta** (não requer aceitar conexões de entrada); servir blocos a terceiros é bloqueável via `BlockRequestAuthoriser` negando tudo.
- Compilação Android (dex, minSdk, APIs JDK, Netty) (tarefa 2.2): **OK.** `poc01/android` (AGP 9.0.1, minSdk 26, compileSdk 36, sem core library desugaring) compila e empacota `assembleDebug` com o nabu v0.8.0 completo. O nabu gera bytecode Java 11, amigável ao D8. Ajustes necessários: (a) unificar BouncyCastle — o nabu traz `bcprov-jdk15on:1.70` (via jvm-libp2p) e `bcprov-jdk18on:1.76` juntos, causando classes duplicadas; resolvido com `dependencySubstitution` para jdk18on; (b) excluir/`pickFirst` de metadados META-INF duplicados (Netty). APK debug resultante: **12 MB** (sem minify).
- Execução em dispositivo real (tarefa 2.3): **OK.** Moto g(30), Android 12 (API 31): stack inicializa sem crash e conecta a **4/4 nós de bootstrap da Amino** com handshake completo (TCP + Noise + yamux + stream `/ipfs/kad/1.0.0`) em modo client puro. Mesmo resultado antes no AVD (Android 17 / API 37). Descoberta: a resolução `/dnsaddr` via dnsjava **falhou no Android** (0 conexões); com endereços `/ip4/...` resolvidos previamente, tudo funciona — o app do Marco 2 precisa resolver dnsaddr por conta própria (DoH ou DNS da plataforma) ou embarcar IPs de bootstrap.

### E1 — Nó pleno discável (JVM)

**Veredito: POSITIVO.** Nó pleno na JVM com endereço público manual (ADR-0006) é discável da internet e serve blocos a um segundo nó em outra rede.

- Bootstrap Amino + DHT como servidor (tarefa 3.1): **OK.** `poc01/node` (EmbeddedIpfs completo, porta 4001) conecta ao bootstrap da Amino, faz o bootstrap Kademlia ("Bootstrap connected to 1 nodes close to us") e roda o provider periódico. Observação: atrás de NAT as conexões caem após o bootstrap (churn normal para nó não-discável) — reforça a necessidade do endereço público (3.2).
- Endereço público manual + provider record (tarefa 3.2): **OK.** Port forwarding TCP 4001 → máquina do E1; nó anuncia `/ip4/177.203.17.5/tcp/4001` via identify e nos provider records (foi preciso montar o `PeerAddresses` com o announce manualmente — `PeerAddresses.fromHost` usa os listen addrs wildcard, indiscáveis).
- Dialabilidade de fora + download do bloco (tarefa 3.3): **OK.** (a) check-host.net conectou no TCP 4001 de 3 países; (b) ipfs-check (kubo, infra pública) completou o handshake libp2p pelo endereço público; (c) o app no celular **em outra rede** (hotpot/dados móveis) discou o endereço público, consultou a DHT, baixou manifesto + 3 blocos via bitswap e verificou a assinatura — o critério do Marco 0 fechou pelo caminho público real.

### E2 — DHT client no Android (completo)

**Veredito: POSITIVO.** Resolução de provider record em modo client puro comprovada (rede direta, pública e fria via E5); custo de dados desprezível. Único ponto não fechado: resolução na **Amino em escala**, bloqueada por bugs upstream diagnosticados (abaixo e em E5).

- Resolução de provider record sem servir (tarefa 4.1): **OK via DHT ao nó E1 (privado e público); resolução na Amino em escala NÃO funciona com nabu v0.8.0.** A mecânica (lookup DHT em modo client → provider record → bitswap, sem servir nem aceitar entrada) foi comprovada duas vezes — em rede privada e pelo endereço público de outra rede. Contra a Amino, porém, nem o record do E1 propaga nem um CID de controle com 26 providers públicos (site ipfs.tech, confirmado via delegated routing) é resolvido pelo app. Diagnóstico (investigado a fundo, com QUIC habilitado via commit `0b421b9427` do nabu — posterior à v0.9.1-quic e com build OK no JitPack): o gap tem **três causas empilhadas** no ecossistema nabu/jvm-libp2p:
  1. **Dial de multiaddr `/dns/` não é suportado** pelo jvm-libp2p (`NothingToCompleteException`) — o bootstrap padrão via dnsaddr falha silenciosamente e a routing table começa com ~1 peer. Contornável resolvendo hostnames para `/ip4/` antes do dial (feito na PoC).
  2. **O transporte QUIC do jvm-libp2p (0.19.16-quic) é instável**: dials QUIC sequenciais funcionam (bootstrap 9/9 conectado, incluindo `/quic-v1` — verificado), mas os dials **paralelos** do walk da DHT quebram com `QuicTransport$dial$connFuture$1 is not a @Sharable handler` + `QuicClosedChannelException`. Como o `findProviders` do nabu executa as consultas em paralelo, o walk morre na primeira rodada. Exige patch upstream (marcar o handler `@Sharable` ou serializar dials).
  3. **Bug de convergência no `findProviders` do nabu**: o ID do alvo é criado **sem** sha256 (`Id.create(key)`) enquanto os peers usam `sha256(peerId)` — a ordenação XOR do walk fica incorreta.
  Com o nabu v0.8.0 (TCP-only) soma-se ainda o fato de a maioria dos servidores da Amino ser QUIC-only. Nota: o build do master do nabu também falha no JitPack (mesmo erro das tags 0.9.x); o commit `0b421b9427` é o mais novo utilizável sem build local.
- Medição da sessão de 30 min (tarefa 4.2): **OK.** Moto g(30), tela ligada; 55 lookups de CIDs aleatórios (1 a cada ~33s) em modo client puro conectado a 4 nós da Amino; **tráfego por UID via `TrafficStats`**.

**Medições (tarefa 4.3):**

| Métrica | Medido | Limiar | Passa? |
|---|---|---|---|
| Dados além do conteúdo | **1,09 MB** (rx 0,95 + tx 0,13) em 55 lookups | < 20 MB | **SIM** |

Leitura: o custo de dados do DHT client é desprezível frente ao limiar — uma ordem de grandeza abaixo.

### E3 — Manifesto assinado (Ed25519 + `seq`)

**Veredito: POSITIVO.** Implementado em `poc01/node` (`Manifest.kt` + `ManifestTest.kt`), Ed25519 via BouncyCastle (`Ed25519Signer`) — já no classpath pelo nabu e disponível no Android, ao contrário do provider Ed25519 do JDK. Serialização canônica com length-prefix por campo. 6 testes, todos verdes.

- Assinatura/verificação (tarefa 5.1): **OK** — assinatura do publicador aceita; chave pública de terceiro rejeitada.
- Rejeição de manifesto adulterado (tarefa 5.2): **OK** — teste inverte um bit em **cada posição** dos bytes canônicos; todas as variantes falham na verificação.
- Detecção de rollback via `seq` (tarefa 5.3): **OK** — manifesto autêntico com `seq` ≤ último conhecido é rejeitado (`Rollback`); manifesto com assinatura inválida não "queima" o contador de `seq`.

### E4 — E2E do Marco 0

**Veredito: POSITIVO — inclusive na variante pública real.** Rodou duas vezes: (1) rede privada — nó E1 no host, app no emulador via `10.0.2.2`; (2) **caminho público completo** — app no dispositivo físico em **outra rede** (hotspot/dados móveis, atrás de NAT, sem configuração de entrada) discando o endereço público `/ip4/177.203.17.5/tcp/4001` do E1: descoberta via DHT, download bitswap, verificação Ed25519 e reconstrução do capítulo. Este é exatamente o critério de conclusão do Marco 0 do roadmap. A descoberta usa consulta DHT ao E1 (rede de 2 nós); a descoberta fria multi-nó foi provada depois no E5, e a variante "Amino em escala" segue bloqueada por bugs upstream (ver E2/4.1 e E5).

- Publicação de capítulo no nó E1 (tarefa 6.1): **OK** — 3 blocos de conteúdo + manifesto assinado (Ed25519, formato do E3) publicados no blockstore e anunciados.
- Descoberta + download + verificação no app (tarefa 6.2): **OK** — `findProviders` via DHT retorna o E1 como provider do manifesto; manifesto e blocos baixados por bitswap diretamente do E1 (app em modo client puro: kad recusa entrada, bitswap com authoriser sempre-false); assinatura verificada contra a chave pública esperada; hash de cada bloco conferido contra o CID do manifesto; capítulo de 3 páginas reconstruído.
- Rejeição de conteúdo corrompido (tarefa 6.3): **OK** — bloco com 1 bit alterado é detectado (hash ≠ CID do manifesto) e manifesto com 1 bit alterado falha na verificação de assinatura.

Log do app (resumo): `DESCOBERTA OK → ASSINATURA OK → CAPÍTULO RECONSTRUÍDO (3 páginas) → REJEIÇÃO OK (bloco) → REJEIÇÃO OK (manifesto) → E4 OK`.

### E5 — Rede bootstrap/DHT própria (adicionado após o diagnóstico do gap Amino)

**Veredito: POSITIVO — a rede própria da OpenToons é viável e a descoberta fria funciona ponta a ponta.**

Topologia: 4 nós plenos na mesma máquina (bootstrap `:4001`, publicador `:4002`, 2 servidores DHT `:4003/4004`), todos com endereço público via port forwarding da faixa 4000-4999 e **identidades determinísticas por porta** (peerIds deriváveis, viabilizando a malha a priori). Cliente: app no dispositivo físico, **em outra rede** (hotspot), em modo client puro.

Resultado (tarefa 8.3): o app, conhecendo **somente** o multiaddr do bootstrap e o CID do manifesto, resolveu o provider record na DHT própria, aprendeu o publicador (peerId + endereço público, nunca informados), discou-o, baixou manifesto + blocos via bitswap, verificou a assinatura e rejeitou conteúdo adulterado. `E5 OK`.

**Reconfirmação em VPS pública real (rede separada).** A rede DHT própria foi resubida numa
VPS Ubuntu de IP público direto (`143.95.220.165`, sem NAT hairpin, sem co-localização): 2 nós
nabu `private`-mesh com identidades determinísticas e announce do IP público. Do desktop, por
outra rede, o `LookupClient` fez descoberta fria — `findProviders(testCid)` retornou **4
providers em 84 ms** com endereços públicos — e o `FetchClient` discou o provider **descoberto**
e baixou o bloco por bitswap (íntegro). Confirma o E5 pela internet real. Atrito de ambiente
registrado: o hostname da VPS não resolvia localmente (`MDnsDiscovery.getLocalHost` quebrava o
`EmbeddedIpfs.start`) — resolvido com entrada em `/etc/hosts`.

**Bugs do nabu v0.8.0 descobertos no caminho (todos com workaround na PoC e candidatos a fix upstream):**

1. **Race no `Kademlia.provideBlock`** — o `ADD_PROVIDER` é enviado via `thenCompose` na conclusão do future do controller, antes de a negociação do stream assentar, e é **descartado em silêncio** (as exceções do método são engolidas e ele reporta sucesso). Com `join()` separando negociação e envio (ou +100ms), funciona 100%. Comprovado por teste JUnit in-process (`DhtColdDiscoveryTest`). **Este bug explica também por que os provider records nunca chegaram à Amino nos testes do E1/4.1.** Workaround: `provideBlockWorkaround` em `poc01/node`.
2. **Conexões de entrada não entram no router** — `KademliaEngine.addIncomingConnection` é no-op, e o caminho compensatório (identify reverso no `HostBuilder`) nunca executa porque o endereço efêmero é gravado no addressBook *antes* da checagem `existing.isEmpty()`. Consequência: topologia estrela passiva não forma DHT; a rede precisa de **malha de dials de saída** entre nós plenos (rediscada periodicamente — implementado nos nós da PoC). Nota: o no-op de entrada até *favorece* o ADR-0005 — clientes móveis nunca entram no router de ninguém.
3. **Clientes montados à mão precisam registrar o protocolo identify** (`IdentifyBuilder.addIdentifyProtocol`) — sem ele o walk falha com `NoSuchLocalProtocolException (/ipfs/id/1.0.0)`. Corrigido no app e nos harnesses.

## Recomendação de stack para o Marco 2

**Recomendação: adotar a stack nabu/jvm-libp2p.** Todas as premissas do Marco 0 foram validadas em condições reais — dispositivo físico, endereço público, redes distintas — e nenhum plano B (gomobile/UniFFI) se mostrou necessário:

- Compila, dexa e executa no Android (API 31 físico e API 37 emulador), minSdk 26, APK debug 12 MB.
- DHT client puro viável (binding customizado de ~15 linhas); custo de dados desprezível (1,09 MB em 30 min — limiar de 20 MB). (Bateria retirada do relatório: medição não confiável.)
- Nó pleno discável por endereço público manual (ADR-0006) funciona; E2E completo (descoberta DHT → bitswap → Ed25519) fechou pelo caminho público real.

**Ações obrigatórias para o Marco 2** (nenhuma invalida a stack):

1. **Rede bootstrap/DHT própria da OpenToons — VALIDADA no E5 e recomendada como caminho principal.** Descoberta fria ponta a ponta provada com nós públicos + malha de dials de saída + workaround do race do provide. Requisitos operacionais comprovados: nós plenos com endereço público (ADR-0006), lista de bootstrap conhecida, malha rediscada periodicamente.
2. **Descoberta na Amino em escala** (integração futura, opcional): exige as correções upstream no ecossistema nabu/jvm-libp2p — o **race do `provideBlock`** (bug nº 1 do E5 — principal suspeito das falhas de provide na Amino; retestar com o workaround), dial de `/dns/` (contorno pronto: resolver IPs antes), estabilizar o QUIC do jvm-libp2p para dials paralelos (`@Sharable`) e corrigir o hash do alvo no `findProviders`. O nabu com QUIC é obtível sem build local via JitPack no commit `com.github.Peergos:nabu:0b421b9427` (porém esse commit regrediu a resposta de GET_PROVIDERS — ver Desvios; usar v0.8.0 + workarounds ou build local com fixes).
3. Resolução `/dnsaddr` própria no Android (dnsjava não acha os resolvers do sistema) — DoH ou API da plataforma, ou IPs de bootstrap embarcados.
4. Binding kad client-mode (o da PoC serve de base); identify de client não deveria anunciar `/ipfs/kad/1.0.0` (patch/upstream).
5. Unificação do BouncyCastle (jdk18on) e verificação da lista transitiva de licenças no empacotamento.
6. Verificar interop bitswap com kubo (o probe WANT-HAVE do ipfs-check não obteve resposta do nabu; nabu↔nabu funciona).

## Desvios e observações

- **Spike E2 rodou primeiro em emulador (AVD) e depois foi confirmado em dispositivo físico** (Moto g(30), Android 12) com o mesmo resultado: 4/4 nós de bootstrap conectados em modo client puro.
- **`/dnsaddr` não resolve no Android via dnsjava** (dnsjava não encontra os resolvers do sistema no Android). Contornado no spike com IPs resolvidos previamente. Para o Marco 2: resolver TXT `_dnsaddr.*` via DoH ou API da plataforma, ou embarcar lista de bootstrap com IPs.
- **Anunciar provider record com routing table fria é inócuo**: o `ADD_PROVIDER` vai para os peers "mais próximos" conhecidos, que logo após o bootstrap são ~1. O nó da PoC aquece a tabela (90s no modo Amino) e reanuncia a cada 60s. Nota: a causa dominante das falhas de provide acabou sendo o **race do `provideBlock`** (ver E5), que se soma a esta.
- **JitPack**: nabu 0.8.1 e 0.9.x-quic estão com build quebrado no JitPack (jul/2026); v0.8.0 é a mais nova utilizável sem build local.
- **BouncyCastle duplicado** (`bcprov-jdk15on` + `bcprov-jdk18on` transitivos do nabu) quebra o build Android; resolvido com `dependencySubstitution` para jdk18on.
- **E4 rodou primeiro em rede privada** (host + emulador via `10.0.2.2`, fallback previsto no design D2) e depois foi repetido com sucesso pelo **caminho público real** (dispositivo físico em outra rede → endereço público), quando o port forwarding ficou disponível.
- **O commit `0b421b9427` do nabu (pós-QUIC) regrediu o GET_PROVIDERS**: consultas diretas que funcionam no v0.8.0 retornam vazio nele. Por isso `poc01/node` voltou ao v0.8.0 (+ workarounds); o commit QUIC fica registrado apenas como fonte do diagnóstico de transporte.
- **Bitswap do nabu não re-emite want de bloco já recebido na mesma sessão** (segundo `get` do mesmo CID com `addToLocal=false` dá timeout). Irrelevante para o produto (blocos vão para o blockstore), mas vale saber no Marco 2.
