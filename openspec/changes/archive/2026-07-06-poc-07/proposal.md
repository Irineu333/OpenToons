## Why

Toda a stack de rede das POCs anteriores (poc-02…poc-06 — Trama: Noise XX, RPC de
frames, membership) é **Kotlin/JVM**: depende de `java.net.Socket`, de
`org.bouncycastle.*` e de `java.util.concurrent`. O Android herda isso de graça
(Android *é* Kotlin/JVM); **iOS não** — Kotlin/Native não tem nenhuma dessas APIs, e
todo iOS ficou **fora de escopo** da poc-01 à poc-06. A dúvida arriscada, ainda não
medida, é: **o mobile realmente cabe em KMP para Android E iOS** — isto é, o caminho
nativo (verify + transporte) fecha um E2E num iPhone real como o Moto g30 fez? E
mais: se a rede tem de existir atrás de uma **SPI comum com implementação trocável no
código** (Trama × libp2p), essa SPI sobrevive à travessia JVM→Native satisfeita por
duas implementações radicalmente diferentes? Só se sabe medindo, em rede real.

## What Changes

- Constrói uma **SPI de rede comum em `commonMain`** (`P2pBackend`/`FullNode`/
  `Blockstore`/`FrameTransport`, sem `java.*`), com a implementação **selecionada por
  código** (factory/DI, não build-variant) e o **verify (Ed25519 + sha256) FORA do
  seam** — neutro, mesmo código entre implementações (regra D7 do poc-03).
- **Porta a Trama para KMP** (motor em `commonMain`; crypto e socket atrás de seams
  `expect/actual` ou libs cross-platform), de modo que a mesma Trama rode em
  JVM/Android **e** em Kotlin/Native (iOS).
- Executa uma **campanha real end-to-end**, a frio, em **três redes genuinamente
  separadas** — esta máquina (DEV/publicador), a **VPS** (replicador+bootstrap, IP
  público manual, ADR-0006) e o **iPhone real em rede móvel** (leitor Kotlin/Native) —
  com o **Moto g30** como baseline que prova que a SPI não regrediu o JVM.
- Roda em **três células com portões**, cada uma satisfazendo a MESMA SPI: **(1)
  Trama→Native** (clearnet, o crux), **(2) libp2p→iOS** via cinterop C-ABI ao `.a`
  rust (mecanismo de binding NOVO — Android usou JNI/UniFFI), **(3) I2P→iOS** (stretch;
  ⚠ no iOS não há daemon: i2pd embarcado in-process / Network Extension — pode
  transbordar para uma poc-08 sem afundar as células 1–2).
- Cada célula segue o loop **setup → teste → registrar dados → commit**.
- **Regras inegociáveis (direção do usuário), citadas e obedecidas em cada célula:**
  - **implementação real (sem simulação)** — código real por trás da SPI, não stub;
  - **teste real end-to-end (sem truque)** — discar/baixar/verificar de ponta a ponta,
    device real em rede real, nunca loopback disfarçado de "campo";
  - **não supor, nem falsear/inventar** — cada claim carrega classe de evidência
    (`[executado]` / `[dado-só]` / limite declarado); nada de extrapolar de POCs
    anteriores como se fosse dado desta;
  - **não pular/ignorar testes** — um teste só é pulado quando **fisicamente
    impossível** de executar nesta bancada, e a impossibilidade é registrada.
- **Não** altera arquitetura de produção nem reescreve ADRs — o poc gera conhecimento
  e uma recomendação (inclusive sobre o eixo NOVO: portabilidade iOS pode pesar a
  favor do libp2p, invertendo o "gatilho invertido" pró-Trama do poc-05/06?).

## Capabilities

### New Capabilities

- `kmp-p2p-spi`: SPI de rede em `commonMain` (sem `java.*`), com verify fora do seam,
  a Trama portada para Kotlin/Native, e um segundo backend (libp2p via cinterop-rust)
  selecionável **no código**; um **TCK de correção** é o contrato/portão, e precisa
  ficar verde no MESMO alvo iOS contra cada implementação **antes** de qualquer medição.
- `ios-native-campaign`: campanha real, a frio, em três redes separadas (DEV + VPS +
  iPhone em dados móveis; Moto g30 como baseline JVM), com limiares fixados **a
  priori** e classes de evidência, cobrindo o setup empírico do skew Xcode/iOS, o E2E
  de leitura (verify Ed25519 no device), o não-vazamento, e as três células
  (Trama→Native, libp2p→iOS, I2P→iOS/stretch) com seus portões.
- `poc07-report`: relatório com conclusão em quatro partes obrigatórias (viabilidade
  técnica; prós e contras; comparação com a arquitetura documentada, ADR a ADR;
  aprendizado e recomendação — incluindo o veredicto sobre o eixo de portabilidade iOS
  Trama × libp2p), cada uma rastreável a testes executados.

### Modified Capabilities

<!-- Nenhuma: este poc não altera requisitos de capabilities de produção; produz
     conhecimento e uma recomendação. Alterações de ADR/arquitetura, se houver, são
     mudanças posteriores derivadas da recomendação. -->

## Impact

- **Novos módulos** (no molde do poc-06): `poc07/api` (a SPI em `commonMain`),
  `poc07/trama` (KMP), `poc07/libp2p` (KMP fino sobre cinterop), `poc07/node`,
  `poc07/rig`, e uma casca de app iOS mínima (harness do probe), registrados no
  `settings.gradle.kts`. Reuso do seam do poc-04/06, não redesenho.
- **Novos alvos KMP**: `iosArm64` (device) no módulo `shared`/`api`/`trama`; cadeia
  cinterop para o `.a` rust em `aarch64-apple-ios`.
- **Dependência externa nova**: uma lib de crypto KMP para Kotlin/Native (Ed25519 +
  sha256 + primitivas Noise) — a escolher/de-riscar num spike, não assumida.
- **Recursos de execução** (todos verificados presentes): shell/JVM desta máquina;
  Moto g30 no USB; **iPhone 11 / iOS 26.5** no WiFi (⚠ skew com Xcode 16.4 — risco #1
  a verificar empiricamente no setup); VPS `root@143.95.220.165:22022` (1 vCPU/1.9 GB).
- **Entregável**: `docs/pocs/poc07-report.md`. Sem impacto em código de produção.
- **Referências cruzadas**: ADR-0005 (mobile DHT client), ADR-0006 (NAT/endereço
  público manual), poc-03 (peso rust/verify fora do seam), poc-04 (SPI/TCK/backend
  trocável), poc-05/06 (gatilho invertido pró-Trama — que este poc reabre pelo eixo iOS).
