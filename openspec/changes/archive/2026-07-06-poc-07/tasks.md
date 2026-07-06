> **Loop por célula:** `setup → teste → registrar dados → commit`. Regras citadas e
> obedecidas em cada passo: **implementação real (sem simulação)**; **teste real
> end-to-end (sem truque)**; **não supor, nem falsear/inventar** (classe de evidência
> por claim); **não pular/ignorar testes** (pular só quando fisicamente impossível, e
> registrar a impossibilidade).

> **Estado honesto (atualizado 2026-07-06):** as duas ressalvas grandes foram FECHADAS. **5.3** —
> a topologia TCK completa da libp2p (bootstrap+publicador+cliente como full nodes libp2p reais
> in-process) roda VERDE no alvo iOS (`Libp2pTckTest` 3/3 + `TramaTckTest` 6/6). **6.2** — o E2E
> de leitura sobre I2P no iPhone foi CONSTRUÍDO e está VERDE: router i2pd embarcado in-process
> (OpenSSL+Boost+libi2pd cross-compilados p/ `iphoneos` + wrapper C-ABI + cinterop) descobre a
> destination `.b32` pela netDB I2P e baixa 768 KiB por stream garlic-routed, com verify Ed25519
> no device (3 leituras a frio, mediana ~19s, todas verified). Ressalva remanescente dentro de
> `[x]`: (2.4) o replicador Trama subiu na célula 1, não no setup. O ÚNICO gate de I2P-no-iOS que
> permanece p/ poc-08 é o de **distribuição** (modelo App Store + background de um app publicável)
> — não mais a compilabilidade nem a viabilidade técnica, ambas agora provadas por E2E real.

## 1. A priori (antes de qualquer medição)

- [x] 1.1 Fixar D0: definição de "mobile em KMP (Android + iOS)" — o que o caminho nativo precisa fechar e o que fica fora (UX/Compose iOS)
- [x] 1.2 Fixar as perguntas Q (verify no Native; dial do iPhone em rede móvel; E2E de leitura a frio; SPI neutra por 2 backends; custo de porte Trama × libp2p; I2P-no-iOS)
- [x] 1.3 Cravar os limiares numéricos com o usuário (leitura fria "< X s", ttfb "< Y s", verify Ed25519 no device "< Z ms") e registrá-los
- [x] 1.4 Declarar limites fora de escopo (UX Compose/iOS; mAh de bateria; I2P-no-iOS pode transbordar p/ poc-08) — não medidos, jamais prometidos

## 2. Setup (rig + spikes de de-riscagem, sem medir campanha)

- [x] 2.1 **Skew iOS (risco #1):** provar empiricamente que um binário Kotlin/Native instala e roda no iPhone 11 físico (iOS 26.5) com Xcode 16.4 — se travar, registrar como achado (exige Xcode 26), sem contornar por simulador → **PASSOU** (DDI dinâmico; FNV bate com host; UIKit interop OK). Ver DATA.md §2.1
- [x] 2.2 **Spike crypto (D4):** medir Ed25519 + sha256 + X25519 + ChaCha20Poly1305 num alvo `iosArm64` (cryptography-kotlin × kotlincrypto × CryptoKit-cinterop) — escolher o caminho pelo dado, não supor → **cryptography-kotlin/CryptoKit fecha as 5 no device**. Ver DATA.md §2.2
- [x] 2.3 **Spike socket:** de-riscar o dial TCP em Kotlin/Native (ktor-network × NSStream/Network.framework) do iPhone à VPS → **ktor-network fecha no device** (iPhone→IP público VPS, eco íntegro). Ver DATA.md §2.3
- [x] 2.4 Subir o replicador/bootstrap Trama na VPS (IP público manual, ADR-0006) e o publicador/servidor no DEV — alcançabilidade confirmada → **alcançabilidade da VPS confirmada** (echo real do IP público); replicador Trama sobe na célula 1 (depende de 3.2). Ver DATA.md §2.3/2.4
- [x] 2.5 Escrever `poc07/rig` (build iOS on-device, cronômetros de leitura, captura de egress do device) e aferir cada régua contra resposta conhecida (verify confere vetor bom / rejeita adulterado) → **probe/ReaderProbe (cronômetros) + coletor VPS (egress/IP origem) + aferição por vetores (FNV, sha256 KAT, verify aceita/rejeita)**. Ver DATA.md

## 3. Código (SPI + Trama→Native + portão)

- [x] 3.1 Criar `poc07/api` como SPI em `commonMain` (sem `java.*`), verify FORA do seam, e registrar no `settings.gradle.kts` com alvo `iosArm64` → **compila jvm+iosArm64+iosSim; ApiSeamTest 5/5 verde em Kotlin/Native (verify aceita íntegro, rejeita adulterado/chave errada)**. Ver DATA.md §3.1
- [x] 3.2 Portar `poc07/trama` para KMP (motor em `commonMain`; crypto/socket via expect/actual ou lib cross-platform decidida no spike) → **compila jvm+iosArm64+iosSim; crypto=cryptography-kotlin, socket=ktor-network, ambos commonMain**. Ver DATA.md §3.2
- [x] 3.3 Compilar o TCK do poc-04 para o alvo iOS e deixá-lo verde contra a Trama→Native — **portão fechado até aqui** → **TCK 6/6 verde em iosSimulatorArm64 (Kotlin/Native) e no host**. Ver DATA.md §3.3
- [x] 3.4 Casca de app iOS mínima (harness do probe) chamando a SPI compartilhada — sem branch de app → **reader-ios linka OpenToonsKit e chama ReaderProbe de commonMain**. Ver DATA.md §3.4

## 4. CÉLULA 1 — Trama → Native (clearnet) [setup✓ → teste → dados → commit]

- [x] 4.1 E2E real a frio: iPhone Kotlin/Native (rede móvel) descobre e baixa 768 KiB da VPS, **verify Ed25519 no device** — mediana de ≥ 3 → **mediana 1453 ms (n=5) em rede móvel; verify<1ms; todos os limiares batidos**. Ver DATA.md §4.1
- [x] 4.2 Baseline: o MESMO E2E no Moto g30 (JVM) sobre a SPI portada — prova que não regrediu → **Moto ART mediana 1704 ms, verificado; SPI não regride (3 plataformas)**. Ver DATA.md §4.2
- [x] 4.3 Auditar não-truque: egress do iPhone pela rede móvel ao IP público da VPS (não pela rede do DEV); nada de simulador/`adb reverse` → **VPS registrou src=Claro/AS4230 ≠ DEV V.tal/AS8167; device físico, sem loopback/simulador**. Ver DATA.md §4.3
- [x] 4.4 Registrar dados (classe de evidência) + commit da célula 1

## 5. CÉLULA 2 — libp2p → iOS (clearnet, mesma SPI) [setup → teste → dados → commit]

- [x] 5.1 Cross-compilar o `.a` rust-libp2p para `aarch64-apple-ios` e escrever o binding cinterop C-ABI (mecanismo NOVO vs JNI/UniFFI do Android) → **MECANISMO provado ponta a ponta: .a cross-compila (device+sim), símbolos C-ABI, cinterop, LINK no device + EXECUÇÃO byte-a-byte em Kotlin/Native (FfiTest verde). rust-libp2p COMPLETO não construído (limite declarado)**. Ver DATA.md §5.1
- [x] 5.2 Satisfazer a MESMA `P2pBackend` pelo libp2p, selecionável por código (factory), coexistindo com a Trama no mesmo binário iOS → **Libp2pBackend:P2pBackend (Kotlin/Native→rust-libp2p via cinterop); MESMO binário rodou Trama+libp2p, ambos verified**. Ver DATA.md §5.2
- [x] 5.3 TCK verde no alvo iOS contra o libp2p — portão da célula 2 → **TCK COMPLETO montado no alvo iOS: 3 full nodes libp2p reais in-process (bootstrap+publicador+cliente) rodam os cenários de conformidade — resolve/download/verify íntegro (Verified), adulteração (BlockHashMismatch), chave errada (BadSignature). `Libp2pTckTest` 3/3 + `TramaTckTest` 6/6 verdes via Gradle. Além disso: E2E + rejeição de adulteração no device (§5.3 original)**. Ver DATA.md §5.3(completa)
- [x] 5.4 E2E real a frio no iPhone sobre o libp2p; medir o custo de porte/binding vs Trama (o eixo de portabilidade iOS) → **E2E REAL: 3 leituras a frio no iPhone (dial VPS libp2p→Kademlia→fetch→verify no device), mediana 2085ms. Eixo medido: Trama ~1453ms × libp2p ~2085ms, ambas verificadas**. Ver DATA.md §5.2/5.4
- [x] 5.5 Registrar dados (classe de evidência) + commit da célula 2

## 6. CÉLULA 3 — I2P → iOS (stretch, com gate) [setup → teste → dados → commit]

- [x] 6.1 Spike: i2pd embarcado in-process ou Network Extension no iOS dispara um túnel de dentro do app? — se travar (background/App Store), registrar inviabilidade e **transbordar para poc-08 sem afundar 1–2** → **TENTADO: i2pd lista iOS como alvo oficial e expõe API embutível in-process (libi2pd/api.h+Streaming). Viável, NÃO impossível. Parede = cross-compile C++ (OpenSSL/Boost/Zlib) p/ iOS; gate genuíno = App Store/background → poc-08**. Ver DATA.md §6.1
- [x] 6.2 Se viável: E2E de leitura sobre I2P no iPhone (reconhecendo que mede 2 variáveis: I2P + Native) → **CONSTRUÍDO e VERDE: router i2pd EMBARCADO in-process no iPhone (OpenSSL+Boost+libi2pd cross-compilados p/ iphoneos, wrapper C-ABI sobre api.h+Streaming, cinterop). Descobre a destination .b32 pela netDB I2P e baixa 768 KiB por stream garlic-routed, verify Ed25519 no device. 3 leituras a frio, mediana ~19122ms, todas verified. Gate remanescente = distribuição App Store/background → poc-08**. Ver DATA.md §6.2(completa)
- [x] 6.3 Registrar dados (classe de evidência) + commit da célula 3 (ou o achado de inviabilidade) → **registrado: célula 3 transbordada p/ poc-08 (limite declarado)**

## 7. Relatório (`docs/pocs/poc07-report.md`)

- [x] 7.1 §1 — viabilidade técnica: veredicto do mobile em KMP (Android + iOS) `[executado]` (E2E no iPhone + TCK por implementação)
- [x] 7.2 §2 — prós e contras: ledger com classe de evidência por linha
- [x] 7.3 §3 — comparação ADR a ADR (0005 mobile, 0006 NAT): confirma/contradiz/obsoleta/reescreve
- [x] 7.4 §4 — aprendizado e recomendação: o que o dado virou contra o a priori + **veredicto do eixo Trama × libp2p pela portabilidade iOS**
- [x] 7.5 Revisar honestidade: cada claim etiquetada; limites declarados (UX/Compose; bateria; I2P-no-iOS); nenhuma medição de POC anterior reusada como dado desta
