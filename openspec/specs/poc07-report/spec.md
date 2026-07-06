# poc07-report Specification

## Purpose
TBD - created by archiving change poc-07. Update Purpose after archive.
## Requirements
### Requirement: Relatório com conclusão em quatro partes rastreáveis

O poc SHALL produzir `docs/pocs/poc07-report.md` com uma conclusão em quatro partes
obrigatórias, cada uma rastreável a testes executados, e cada claim com classe de
evidência (`[executado]` / `[dado-só]` / limite declarado).

#### Scenario: §1 Viabilidade técnica

- **WHEN** a conclusão §1 afirma a viabilidade do mobile em KMP (Android + iOS)
- **THEN** o veredicto é ancorado no E2E executado no iPhone real (verify no device) e
  no TCK verde por implementação, não em raciocínio

#### Scenario: §2 Prós e contras

- **WHEN** a conclusão §2 lista os sinais
- **THEN** é um ledger com classe de evidência por linha, ligado ao teste que decide

#### Scenario: §3 Comparação com a arquitetura documentada

- **WHEN** a conclusão §3 confronta os ADRs (0005 mobile, 0006 NAT)
- **THEN** cada um é marcado confirma/contradiz/obsoleta/reescreve, ligado ao teste

#### Scenario: §4 Aprendizado e recomendação, com o veredicto do eixo iOS

- **WHEN** a conclusão §4 recomenda
- **THEN** ela declara o que o dado virou contra o a priori e o **veredicto do eixo de
  portabilidade iOS Trama × libp2p** (confirma ou inverte o pró-Trama do poc-05/06),
  com o custo de porte medido como base

#### Scenario: Honestidade de evidência

- **WHEN** o relatório é revisado
- **THEN** cada claim está etiquetada, os limites (UX/Compose iOS; mAh de bateria;
  I2P-no-iOS) estão declarados, e nenhuma medição de POC anterior é reusada como dado
  desta

