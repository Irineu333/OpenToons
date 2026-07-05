> **Loop por célula:** `setup → teste → registrar dados → commit`. Regras citadas e
> obedecidas em cada passo: **implementação real (sem simulação)**; **teste real
> end-to-end (sem truque)**; **não supor, nem falsear/inventar** (classe de evidência
> por claim); **não pular/ignorar testes** (pular só quando fisicamente impossível, e
> registrar a impossibilidade).

## 1. A priori (antes de qualquer medição)

- [ ] 1.1 Fixar D0: definição de "mobile em KMP (Android + iOS)" — o que o caminho nativo precisa fechar e o que fica fora (UX/Compose iOS)
- [ ] 1.2 Fixar as perguntas Q (verify no Native; dial do iPhone em rede móvel; E2E de leitura a frio; SPI neutra por 2 backends; custo de porte Trama × libp2p; I2P-no-iOS)
- [ ] 1.3 Cravar os limiares numéricos com o usuário (leitura fria "< X s", ttfb "< Y s", verify Ed25519 no device "< Z ms") e registrá-los
- [ ] 1.4 Declarar limites fora de escopo (UX Compose/iOS; mAh de bateria; I2P-no-iOS pode transbordar p/ poc-08) — não medidos, jamais prometidos

## 2. Setup (rig + spikes de de-riscagem, sem medir campanha)

- [ ] 2.1 **Skew iOS (risco #1):** provar empiricamente que um binário Kotlin/Native instala e roda no iPhone 11 físico (iOS 26.5) com Xcode 16.4 — se travar, registrar como achado (exige Xcode 26), sem contornar por simulador
- [ ] 2.2 **Spike crypto (D4):** medir Ed25519 + sha256 + X25519 + ChaCha20Poly1305 num alvo `iosArm64` (cryptography-kotlin × kotlincrypto × CryptoKit-cinterop) — escolher o caminho pelo dado, não supor
- [ ] 2.3 **Spike socket:** de-riscar o dial TCP em Kotlin/Native (ktor-network × NSStream/Network.framework) do iPhone à VPS
- [ ] 2.4 Subir o replicador/bootstrap Trama na VPS (IP público manual, ADR-0006) e o publicador/servidor no DEV — alcançabilidade confirmada
- [ ] 2.5 Escrever `poc07/rig` (build iOS on-device, cronômetros de leitura, captura de egress do device) e aferir cada régua contra resposta conhecida (verify confere vetor bom / rejeita adulterado)

## 3. Código (SPI + Trama→Native + portão)

- [ ] 3.1 Criar `poc07/api` como SPI em `commonMain` (sem `java.*`), verify FORA do seam, e registrar no `settings.gradle.kts` com alvo `iosArm64`
- [ ] 3.2 Portar `poc07/trama` para KMP (motor em `commonMain`; crypto/socket via expect/actual ou lib cross-platform decidida no spike)
- [ ] 3.3 Compilar o TCK do poc-04 para o alvo iOS e deixá-lo verde contra a Trama→Native — **portão fechado até aqui**
- [ ] 3.4 Casca de app iOS mínima (harness do probe) chamando a SPI compartilhada — sem branch de app

## 4. CÉLULA 1 — Trama → Native (clearnet) [setup✓ → teste → dados → commit]

- [ ] 4.1 E2E real a frio: iPhone Kotlin/Native (rede móvel) descobre e baixa 768 KiB da VPS, **verify Ed25519 no device** — mediana de ≥ 3
- [ ] 4.2 Baseline: o MESMO E2E no Moto g30 (JVM) sobre a SPI portada — prova que não regrediu
- [ ] 4.3 Auditar não-truque: egress do iPhone pela rede móvel ao IP público da VPS (não pela rede do DEV); nada de simulador/`adb reverse`
- [ ] 4.4 Registrar dados (classe de evidência) + commit da célula 1

## 5. CÉLULA 2 — libp2p → iOS (clearnet, mesma SPI) [setup → teste → dados → commit]

- [ ] 5.1 Cross-compilar o `.a` rust-libp2p para `aarch64-apple-ios` e escrever o binding cinterop C-ABI (mecanismo NOVO vs JNI/UniFFI do Android)
- [ ] 5.2 Satisfazer a MESMA `P2pBackend` pelo libp2p, selecionável por código (factory), coexistindo com a Trama no mesmo binário iOS
- [ ] 5.3 TCK verde no alvo iOS contra o libp2p — portão da célula 2
- [ ] 5.4 E2E real a frio no iPhone sobre o libp2p; medir o custo de porte/binding vs Trama (o eixo de portabilidade iOS)
- [ ] 5.5 Registrar dados (classe de evidência) + commit da célula 2

## 6. CÉLULA 3 — I2P → iOS (stretch, com gate) [setup → teste → dados → commit]

- [ ] 6.1 Spike: i2pd embarcado in-process ou Network Extension no iOS dispara um túnel de dentro do app? — se travar (background/App Store), registrar inviabilidade e **transbordar para poc-08 sem afundar 1–2**
- [ ] 6.2 Se viável: E2E de leitura sobre I2P no iPhone (reconhecendo que mede 2 variáveis: I2P + Native)
- [ ] 6.3 Registrar dados (classe de evidência) + commit da célula 3 (ou o achado de inviabilidade)

## 7. Relatório (`docs/pocs/poc07-report.md`)

- [ ] 7.1 §1 — viabilidade técnica: veredicto do mobile em KMP (Android + iOS) `[executado]` (E2E no iPhone + TCK por implementação)
- [ ] 7.2 §2 — prós e contras: ledger com classe de evidência por linha
- [ ] 7.3 §3 — comparação ADR a ADR (0005 mobile, 0006 NAT): confirma/contradiz/obsoleta/reescreve
- [ ] 7.4 §4 — aprendizado e recomendação: o que o dado virou contra o a priori + **veredicto do eixo Trama × libp2p pela portabilidade iOS**
- [ ] 7.5 Revisar honestidade: cada claim etiquetada; limites declarados (UX/Compose; bateria; I2P-no-iOS); nenhuma medição de POC anterior reusada como dado desta
