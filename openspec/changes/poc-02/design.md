# Design: poc-02 — Implementação própria da camada de rede

## Context

O poc-01 ([relatório](../../../docs/poc-report.md)) validou a stack nabu/jvm-libp2p em condições reais, mas expôs seu custo: quatro bugs upstream diagnosticados, workarounds em todas as camadas, upstream sem builds utilizáveis no JitPack e recomendação final de **rede própria** — o que elimina o principal valor do libp2p (interop com IPFS/Amino). A Amino, por sua vez, só era necessária para validar comportamento em escala que uma rede pequena não exibe; esse papel pode ser cumprido por simulação.

A arquitetura da OpenToons dispensa, por decisão de design, a maior parte do que o libp2p resolve:

- [ADR-0006](../../../docs/decisions/0006-nat-and-reachability.md): nó pleno tem endereço público manual → **sem NAT traversal**;
- [ADR-0005](../../../docs/decisions/0005-mobile-client.md): mobile só faz conexões de saída e nunca serve → **sem aceitar entrada no cliente**;
- [ADR-0001](../../../docs/decisions/0001-network-model.md): rede assimétrica, só nós plenos sustentam a malha → **população de malha pequena** (centenas a milhares por anos);
- tráfego integralmente request/response (consulta de descoberta, get de bloco) → **sem muxer de streams arbitrários**.

A stack mínima necessária tem cinco camadas; duas já estão validadas ou são triviais:

```
Descoberta      gossip de membership OU Kademlia enxuto   ← decisão via E3
RPC             frames length-prefixed + request-id       ← trivial (E2)
Canal seguro    TLS 1.3 OU Noise XX                       ← risco real (E1)
Identidade      Ed25519 (E3 do poc-01, sem mudança)       ← pronto
Transporte      TCP (ktor-network / java.nio)             ← trivial
```

## Goals / Non-Goals

**Goals:**

- Gerar **dados comparativos para decisão**, com limiares fixados a priori:
  (a) TLS 1.3 × Noise XX para o canal seguro; (b) membership+gossip × Kademlia próprio para a descoberta; (c) implementação própria × nabu+workarounds como stack do Marco 2.
- Provar que a stack própria fecha o **mesmo critério E2E do Marco 0** que o nabu fechou (nó público → mobile em outra rede, descoberta fria, verificação, rejeição).
- Produzir `docs/poc02-report.md` com as matrizes de decisão preenchidas com dados medidos — o artefato durável.
- Comparabilidade estrita com o poc-01: mesmo dispositivo, mesmo roteiro de sessão, mesmos limiares de bateria/dados.

**Non-Goals:**

- Qualquer código de produto — nada em `shared`/`desktopApp`.
- Interoperabilidade com libp2p/IPFS/Amino em nível de wire — a Amino era veículo de validação em escala, papel agora cumprido pela simulação do E3.
- Hole punching / relay (ADR-0006 mantém para o marco 4), QUIC, transporte além de TCP.
- Formato final de wire/manifesto — a PoC valida mecanismos, não schemas definitivos.
- Robustez de produção, hardening, cobertura exaustiva — código descartável com o rigor mínimo para conclusões críveis (exceção: as variantes do E1 exigem os testes de segurança listados na spec, ou a comparação não é crível).

## Decisions

### D1 — "Sem biblioteca" significa sem framework P2P; libs comuns são permitidas

Zero-dependências sacrificaria KMP e reinventaria o trivial. Permitidos: BouncyCastle (primitivas cripto — já em uso desde o E3 do poc-01), ktor-network ou java.nio (sockets), kotlinx.serialization (formato de wire). Proibidos: nabu, jvm-libp2p, Netty e qualquer framework de rede P2P. Conscrypt entra **somente** se o E1a comprovar necessidade para TLS 1.3 em minSdk 26–28, e o custo em MB é registrado como dado.

### D2 — E1 implementa e compara AMBOS os canais (TLS 1.3 e Noise XX)

Os dois entregam o mesmo resultado de segurança (autenticação mútua ligada à identidade Ed25519 + confidencialidade + integridade); diferem em onde mora o risco. Análise a priori que o experimento vai confirmar ou refutar com dados:

| Critério | E1a — TLS 1.3 (plataforma) | E1b — Noise XX (sobre BouncyCastle) |
|---|---|---|
| Cripto artesanal | Zero (JSSE/Conscrypt, testado em campo) | Handshake + transporte à mão (~400–600 LoC) |
| Rede de proteção | A plataforma | Vetores de teste oficiais do Noise cobrem o handshake; nonces/transporte ficam por conta do projeto |
| Ligação identidade↔canal | Contorno necessário: X.509 autoassinado com extensão contendo pubkey Ed25519 + assinatura (esquema do libp2p-tls); exige bcpkix e TrustManager customizado | Natural: chave estática Noise assinada pela identidade Ed25519 (esquema do libp2p-noise, conhecido do poc-01) |
| Android minSdk 26 | TLS 1.3 nativo só API 29+; API 26–28 → Conscrypt (+MB) ou TLS 1.2 | BC já no classpath; X25519 + ChaCha20-Poly1305 + HKDF inclusos; zero dependência nova |
| Reconexão mobile | Session resumption de graça (menos RTT/bateria no padrão abre-app→lê→fecha) | Handshake completo (1.5 RTT) a cada reconexão; 0-RTT (padrão IK) só com trabalho extra |
| KMP futuro (iOS) | expect/actual de stacks TLS distintas (JSSE × Network.framework), comportamentos divergentes | Protocolo em commonMain; só primitivas viram expect/actual — caminho mais limpo |
| Depuração | Wireshark decoda; erros de SSLEngine crípticos | Controle total dos bytes; bugs são no código do projeto |
| Manutenção | Patches de segurança vêm da plataforma | Responsabilidade perpétua do projeto |

Leitura: o risco do TLS é **de integração**; o do Noise é **de correção criptográfica**. Empate a priori — só dados desempatam. Cada variante tem **veto de esforço de 5 dias úteis** (D5); estourar ambos é resultado válido ("implementação própria custa mais do que parece").

### D3 — E3 implementa e compara AMBAS as descobertas (membership+gossip e Kademlia enxuto); simulação substitui a Amino

A matemática de escala desarma o medo do full view: registro de membership ≈ 128 B → 10.000 nós plenos = 1,3 MB; provider records ≈ 100 B → 100k obras × 10 réplicas = 100 MB (em nó pleno desktop/VPS — nunca no mobile, que é só cliente). E com menos de ~200 nós, **Kademlia degenera em full view** de qualquer forma: paga-se a complexidade do walk iterativo desde o dia 1 para colher o benefício só com dezenas de milhares de nós.

| Critério | E3a — Membership + gossip | E3b — Kademlia próprio |
|---|---|---|
| Lookup do cliente | 1 RTT (pergunta a qualquer nó pleno; 2–3 em paralelo p/ merge, como o roadmap já previa) | Walk iterativo, 3–6 hops × RTT, com tabela fria a cada abertura do app — o custo de bateria mora aqui |
| Descoberta fria | bootstrap → recebe a lista de membros | bootstrap → aquecer tabela (90 s no poc-01) → walk |
| Estado por nó pleno | O(n) — viável até ~10k plenos / ~1M pares obra-réplica | O(log n) — o único ganho real do Kademlia |
| Complexidade | Anti-entropia com digests; simples de testar/depurar; a malha de dials de saída do E5 do poc-01 já é meio caminho; PEX já estava no roadmap | k-buckets, expiry, republish, convergência, churn — o E5 do poc-01 mostrou como é fácil errar (3 bugs no nabu) |
| Papel do cliente | Natural: cliente pergunta, nunca participa | Modo client puro exige tratamento especial (foi o binding-contorno do poc-01) |
| Teto de escala | ~10k nós plenos; degrada suave (gossip fica lento antes de quebrar) | Milhões |
| Migração futura | Interface comum `resolve(obraId) → [providers]` → DHT encaixa no marco 4 sem tocar o resto | — |

O experimento roda os dois em **simulação in-process** (n = 10, 100, 1.000, 10.000 nós; churn injetado) — cumprindo o papel de validação em escala que a Amino cumpria no poc-01, sem depender de rede externa nem de bugs alheios — e o vencedor (ou o candidato designado, se ambos passarem) roda a **descoberta fria real** no E4. Saída-chave: a curva que fixa **em que n o gossip deixa de valer a pena**, virando gatilho documentado de migração no roadmap em vez de chute.

### D4 — RPC por frames com request-id; sem muxer, sem bitswap

Todo o tráfego da OpenToons é request/response (consulta de descoberta, get de manifesto/bloco). Frames length-prefixed com request-id sobre uma única conexão TCP dão multiplexação suficiente sem yamux, e "me dá o bloco H / toma" substitui o bitswap (want-lists, sessões e ledger existem para o caso simétrico geral que a OpenToons não tem). O E2 valida a tese medindo o download do mesmo capítulo de 3 blocos do poc-01, com throughput comparado. Se a tese falhar (ex.: head-of-line blocking inviabilizar downloads paralelos), o dado vai para o relatório e a mitigação (N conexões ou chunking de frames) é medida também.

### D5 — Limiares fixados a priori (comparabilidade com o poc-01 + veto de esforço)

Definidos ANTES de qualquer medição; ajustes exigem justificativa registrada no relatório:

| Métrica | Cenário | Limiar |
|---|---|---|
| Bateria | Mesma sessão do poc-01: 30 min, lookups periódicos, Moto g(30) | **< 5%** |
| Dados móveis | Mesma sessão, tráfego do UID além do conteúdo | **< 20 MB** |
| Handshake | Primeira conexão, dispositivo físico, rede real | **< 1 s** |
| Reconexão | Reconexão subsequente ao mesmo nó | **< 500 ms** |
| Lookup frio (gossip) | Cliente resolve providers a partir só do bootstrap | **≤ 3 RTTs** |
| Delta de APK | Camada de rede própria completa (vs 12 MB do nabu) | **≤ 2 MB** |
| Veto de esforço | Cada variante do E1 (TLS; Noise) | **≤ 5 dias úteis** cada |

O veto de esforço transforma estouro de custo em resultado: se ambas as variantes do E1 estourarem, o relatório recomenda "nabu + workarounds" e a change encerra com resultado negativo — válido.

### D6 — Módulo Gradle `poc02/` isolado; `poc01/` preservado como linha de base

Mesma lógica do D3 do poc-01: dependências e código descartável fora de `shared`/`desktopApp`, trivial de apagar após o relatório. `poc01/` não é tocado — é a referência viva para a comparação de throughput/APK/LoC e a prova de que os números do relatório anterior são reproduzíveis.

### D7 — Ordem: E1 primeiro (risco existencial), E3-simulação em paralelo; E2 → E4 → E5 na sequência

O risco que pode matar a implementação própria é o canal seguro — por isso o E1 abre a PoC, com as duas variantes e o veto de esforço. A simulação do E3 não depende de rede nem do canal (usa transporte in-process) e corre em paralelo. E2 (RPC/blocos) precisa do canal vencedor do E1; E4 (E2E) precisa de E2 + E3; E5 (medições) fecha, nas mesmas condições do poc-01.

### D8 — Reuso do manifesto do poc-01; identidade Ed25519 inalterada

O mecanismo de manifesto assinado (Ed25519 + `seq`, serialização canônica) foi validado no E3 do poc-01 e é independente de stack de rede. O poc-02 reutiliza o formato e o código de verificação sem revalidá-los; a identidade dos nós é o mesmo par Ed25519, agora também ligado ao canal seguro (D2).

## Risks / Trade-offs

- [Cripto artesanal com bug sutil (E1b)] → vetores de teste oficiais do Noise obrigatórios; testes de impostor/adulteração na spec; e a alternativa E1a (TLS, zero cripto artesanal) corre em paralelo — a decisão nunca fica refém de uma variante.
- [Esforço subestimado — o risco central do caminho próprio] → veto a priori de 5 dias/variante no E1 (D5); estourar é dado, não fracasso; horas/dias reais registrados por experimento no relatório.
- [Simulação in-process não representa a rede real] → a simulação decide a **comparação** (escala, churn, convergência); a **viabilidade** é provada no caminho real do E4 (nó público + mobile em outra rede, descoberta fria). Divergências entre simulação e real vão para o relatório.
- [Head-of-line blocking no RPC de conexão única (D4)] → medido no E2 com downloads concorrentes; mitigações (N conexões, chunking) prototipadas só se o problema aparecer.
- [Comparação enviesada a favor da stack própria (código novo, rede limpa)] → mesmos limiares, mesmo dispositivo, mesmo roteiro de sessão e mesmo capítulo de teste do poc-01; números do nabu vêm do relatório publicado, não de re-medição seletiva.
- [Custo de manutenção perpétua de código de segurança — não mensurável por experimento] → registrado como consequência assumida na recomendação final do relatório e no futuro ADR de stack de rede; não entra nas métricas.
- [PoC "vaza" para produto] → `poc02/` fora dos módulos de produto; o relatório, não o código, é o entregável (o código pode, no máximo, servir de referência para o Marco 2).

## Open Questions

- TLS 1.3 com certificado Ed25519 direto funciona no JSSE/Android, ou o certificado precisa ser ECDSA assinando a identidade via extensão? (primeira tarefa do E1a)
- Qual o overhead real de bcpkix (geração de X.509) vs Conscrypt no APK? (registrar no relatório)
- O padrão de anúncio de providers no gossip deve ser por obra ou por manifesto? (decidir no E3a; impacta o volume de registros — a PoC mede os dois se o custo for baixo)
- kotlinx.serialization protobuf ou cbor para o wire? (decidir no E2; irrelevante para as conclusões, registrar o racional)
