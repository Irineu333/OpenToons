## Context

Da poc-01 à poc-06, todo iOS ficou **fora de escopo** e a stack de rede (Trama) foi
escrita em **Kotlin/JVM**: `java.net.Socket`, `org.bouncycastle.*`,
`java.util.concurrent`. Android herda isso por ser Kotlin/JVM; **Kotlin/Native (iOS)
não tem nenhuma dessas APIs**. Este poc mede se o mobile cabe em KMP para **Android E
iOS** por trás de uma **SPI comum trocável no código** (Trama × libp2p), com o verify
fora do seam — e se, ao fazê-lo, a portabilidade iOS reabre a escolha de backend.

Quatro recursos reais definem a topologia (todos verificados presentes):

```
   RECURSO             PAPEL     roda
   ─────────────────────────────────────────────────────────────────────────────
   shell/JVM (DEV)     P         nó pleno · publicador/servidor (clearnet, IP local)
   VPS 143.95.220.165  R + B     nó pleno · replicador + bootstrap (IP público manual)
   Moto g30 (USB)      M-jvm     baseline: leitor Kotlin/JVM (prova que a SPI não regride)
   iPhone 11 (WiFi)    M-native  o CRUX: leitor Kotlin/Native, em rede móvel, a frio

   caminho de leitura = 3 redes REALMENTE separadas:
        P(DEV, residencial) ══clearnet══ R(VPS, datacenter) ══clearnet══ M(iPhone, dados móveis)
```

Princípio inegociável (direção do usuário, citado): **implementação real (sem
simulação); teste real end-to-end (sem truque); não supor, nem falsear/inventar; não
pular/ignorar testes** — pular só quando fisicamente impossível, e registrar a
impossibilidade.

## Goals / Non-Goals

**Goals:**

- Provar que a **Trama roda em Kotlin/Native** e fecha o E2E de leitura (descobrir →
  baixar → **verificar Ed25519 no device**) num iPhone real, em rede móvel, a frio.
- Provar que a **SPI é neutra de implementação**: a MESMA `P2pBackend` em `commonMain`
  é satisfeita por Trama (Kotlin puro) e por libp2p (cinterop → `.a` rust), com o
  MESMO TCK verde no MESMO alvo iOS.
- Medir o **custo real de porte** de cada backend para Native e responder ao eixo NOVO:
  portabilidade iOS pesa a favor do libp2p (invertendo o pró-Trama do poc-05/06)?
- Manter o **Moto g30 como baseline** que prova que mover a Trama para `commonMain` não
  quebrou o caminho JVM/Android já validado.
- Produzir relatório com conclusão em quatro partes rastreáveis a testes.

**Non-Goals:**

- **Não** valida a UX de leitura (Compose/iOS) — o escopo é o caminho nativo, não a
  interface; a viabilidade da UI fica como buraco declarado para um marco-1-iOS.
- **Não** empilha anonimato nas células 1–2: elas rodam **clearnet** para isolar a
  única variável (plataforma JVM→Native). Overlay só na célula 3.
- **Não** altera arquitetura de produção nem reescreve ADRs — só recomenda.
- **Não** promete I2P no iOS: a célula 3 é **spike de viabilidade** com portão; se o
  modelo de daemon/background/App Store travar, transborda para poc-08.

## Decisions

**D1 — Constante = clearnet; variável = plataforma.** As células 1–2 rodam sobre
`TcpTransport` (VPS com IP público manual, ADR-0006), exatamente como o poc-04 rodou
clearnet antes de o poc-05/06 empilharem overlay. Isola "iOS é viável?" de "anonimato
no Native é viável?". *Por quê:* uma POC boa move uma variável por vez.

**D2 — A SPI sobe para `commonMain` e o verify fica FORA dela.** `P2pBackend`/
`FullNode`/`Blockstore`/`FrameTransport` viram tipos `commonMain` sem `java.*`
(`Closeable` → `kotlinx.io`/expect). `ChapterVerifier` (Ed25519 + sha256) roda em
Kotlin/Native, neutro, o MESMO para todos os backends. *Alternativa descartada:*
mover o verify para dentro do `.a` rust encolheria a superfície Native do iOS, mas
**quebra a comparabilidade** entre células (regra D7 do poc-03) — recusada de propósito.

**D3 — Seleção de backend no código (factory), não build-variant.** Ambos os backends
compilam para o mesmo binário iOS; a escolha é uma linha de Kotlin. *Por quê:* o
pedido é "SPI trocável no código"; é o teste mais duro da neutralidade da SPI (dois
backends coexistindo no mesmo alvo).

**D4 — Crypto/socket da Trama: `expect/actual` × lib cross-platform — decidido pelo
spike, não a priori.** Dois caminhos para a Trama cruzar: (a) `expect/actual` por
plataforma (BouncyCastle no JVM/Android, CryptoKit/Security no Apple); (b) lib
cross-platform em `commonMain` (ex.: cryptography-kotlin + ktor-network). O spike de
de-riscagem (task 2.x) mede qual fecha Ed25519/ChaCha20Poly1305/X25519/sha256 no alvo
`iosArm64` antes de comprometer a arquitetura. *Por quê:* "não supor" — a maturidade
Native dessas libs é dado, não palpite.

**D5 — Portão de correção antes de medir (por implementação, no alvo iOS).** Nenhum
número da campanha conta enquanto o TCK do poc-04 não estiver verde compilado para
Kotlin/Native, contra aquela implementação. Correção primeiro, latência real depois.

**D6 — Réguas aferidas (validar o próprio instrumento).** Cada probe é conferido
contra resposta conhecida (ex.: verify Ed25519 do device confere um vetor conhecido e
**rejeita** um manifesto adulterado antes de qualquer medição de tempo). *Por quê:*
"teste tudo" inclui a régua.

**D7 — Limiares fixados a priori, com o usuário.** D0, perguntas Q e limiares numéricos
(leitura fria "< X s", ttfb, verify no device) são cravados **antes** da primeira
medição. Toda claim carrega classe de evidência.

**D8 — Conclusão do relatório em quatro partes obrigatórias**, cada uma rastreável a
testes: viabilidade técnica; prós/contras (ledger com classe de evidência por linha);
comparação ADR a ADR (confirma/contradiz/obsoleta/reescreve); aprendizado e
recomendação — com o **veredicto explícito do eixo Trama × libp2p pela portabilidade iOS**.

## Risks / Trade-offs

- **[Skew Xcode 16.4 × iOS 26.5 no device]** → risco #1. O deploy on-device pode faltar
  device-support files. **Não se supõe** que funciona nem que falha: o primeiríssimo
  teste do setup é um "hello Kotlin/Native" instalar e rodar no iPhone real. Se travar,
  é achado real (exige Xcode 26) e a impossibilidade é registrada, não contornada.
- **[Ed25519/ChaCha em KMP crypto pode não existir/ser lento no Native]** → sustenta
  toda a célula 1. De-riscado por spike no `iosArm64` antes de comprometer D4; se
  nenhuma lib fechar, expect/actual com CryptoKit via cinterop é o fallback medido.
- **[cinterop C-ABI ao `.a` rust é mecanismo NOVO]** → Android usou JNI/UniFFI; o
  roadmap só registrou que as static libs iOS *compilam*, não que o binding fecha. A
  célula 2 mede o binding de verdade, não assume "registrado viável".
- **[Célula 3 mede duas variáveis]** → I2P reintroduz o overlay; a leitura da célula 3
  mistura "I2P no iOS" com "anonimato no Native". Aceito como stretch e declarado;
  gate explícito para não contaminar 1–2.
- **[I2P sem daemon no iOS]** → sem `fork/exec`, background morto fora de foco, revisão
  de App Store. i2pd teria de ser embarcado in-process ou Network Extension. Se travar,
  transborda para poc-08; a célula 3 não segura o resultado das células 1–2.
- **[Simulador disfarçado de device]** → proibido: a campanha de leitura é sempre no
  iPhone físico em rede móvel; o simulador só serve para o portão TCK, nunca para
  número de campo (regra "sem truque").
- **[VPS 1 vCPU/1.9 GB]** → teto registrado; suficiente para replicador TCP clearnet
  (o poc-06 rodou ali).

## Open Questions

- Lib de crypto KMP (cryptography-kotlin × kotlincrypto × CryptoKit-cinterop) e sua
  cobertura Ed25519/ChaCha/X25519 no `iosArm64` — resolvido no spike, não assumido.
- Socket no Native: ktor-network × NSStream/Network.framework via cinterop — decidido
  pela robustez medida do dial iPhone→VPS em rede móvel.
- Valores numéricos dos limiares a priori (leitura fria "< X s", ttfb, verify) — a
  cravar com o usuário antes da primeira medição.
- Se as células 1–2 passarem, incluir ou não a célula 3 (I2P→iOS) nesta rodada ou
  spinar para poc-08.
