# ios-native-campaign Specification

## Purpose
TBD - created by archiving change poc-07. Update Purpose after archive.
## Requirements
### Requirement: Setup empírico do device iOS antes de medir

A campanha SHALL verificar empiricamente, antes de qualquer medição, que um binário
Kotlin/Native instala e roda no iPhone físico (iOS 26.5) a partir do toolchain
disponível (Xcode 16.4) — sem supor que funciona nem que falha.

#### Scenario: Hello Kotlin/Native roda no device real

- **WHEN** um binário Kotlin/Native mínimo é instalado no iPhone 11 físico
- **THEN** ele executa no device; se o skew Xcode/iOS impedir o deploy, a
  impossibilidade é registrada como achado (não contornada por simulador)

### Requirement: E2E de leitura a frio no iPhone real, em rede móvel

A campanha SHALL medir o E2E de leitura (descobrir → baixar → verificar) no iPhone
Kotlin/Native, **a frio**, em **rede móvel** (não WiFi do DEV), contra a VPS real, com
limiares fixados a priori e mediana de ≥ 3 amostras.

#### Scenario: Capítulo verificado no device

- **WHEN** o leitor a frio no iPhone descobre e baixa um capítulo (ex.: 768 KiB) da VPS
- **THEN** o `ChapterVerifier` confere a assinatura Ed25519 **no próprio device** e os
  bytes íntegros, e o tempo total fica sob o limiar a priori

#### Scenario: Baseline JVM não regride

- **WHEN** o MESMO E2E roda no Moto g30 (Kotlin/JVM) sobre a SPI portada
- **THEN** ele passa, provando que mover a Trama para `commonMain` não quebrou o
  caminho JVM/Android já validado nas POCs anteriores

### Requirement: Três células com portões e classes de evidência

A campanha SHALL cobrir três células — (1) Trama→Native clearnet, (2) libp2p→iOS
clearnet, (3) I2P→iOS (stretch) — cada uma após seu TCK verde, com cada claim marcada
`[executado]` / `[dado-só]` / limite declarado, sem extrapolar POCs anteriores como dado.

#### Scenario: Custo de porte comparado (eixo Trama × libp2p)

- **WHEN** as células 1 e 2 fecham o E2E no iPhone real
- **THEN** o custo de cruzar cada backend para Native é registrado com evidência,
  permitindo o veredicto do eixo de portabilidade iOS

#### Scenario: Célula I2P com gate que não afunda 1–2

- **WHEN** a célula 3 (I2P→iOS) esbarra no modelo de daemon/background/App Store
- **THEN** a inviabilidade é registrada e a célula transborda para poc-08, sem
  invalidar os resultados das células 1–2

### Requirement: Não-vazamento e não-simulação auditados

A campanha SHALL provar que a leitura é E2E real (device físico, rede real) e, nas
células clearnet, que o caminho não usa truque (loopback disfarçado, `adb reverse`,
simulador).

#### Scenario: Auditoria de que o device fala com a VPS real

- **WHEN** a leitura roda no iPhone em rede móvel
- **THEN** a evidência mostra o egress do device pela rede móvel ao IP público da VPS
  (não pela rede do DEV), confirmando três redes separadas

