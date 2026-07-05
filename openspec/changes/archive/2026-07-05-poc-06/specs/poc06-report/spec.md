## ADDED Requirements

### Requirement: Conclusão em quatro partes obrigatórias

O relatório (`docs/poc06-report.md`) SHALL conter uma conclusão com exatamente
quatro partes: **viabilidade técnica**, **prós e contras**, **comparação com a
arquitetura documentada**, e **aprendizado e recomendação**. Cada parte SHALL ser
rastreável aos testes que a alimentam.

#### Scenario: Viabilidade técnica com veredicto único

- **WHEN** a parte de viabilidade é escrita
- **THEN** ela apresenta um veredicto único `[executado]` (viável / viável no
  backbone mas inviável no mobile / inviável em camada X) alimentado por T3/T4, T1
  e o portão do TCK

#### Scenario: Prós e contras com classe de evidência por linha

- **WHEN** o ledger de prós e contras é montado
- **THEN** cada linha indica de qual teste vem e sua classe de evidência, sem
  afirmar sinal não medido

#### Scenario: Comparação ADR a ADR

- **WHEN** a comparação com a arquitetura documentada é escrita
- **THEN** cada ADR relevante (0001, 0002, 0005, 0006, 0007) é marcado como
  confirmado / contraditado / obsoletado / a reescrever, ligado ao teste que decide

#### Scenario: Aprendizado e recomendação ancorados no a priori

- **WHEN** a parte de aprendizado e recomendação é escrita
- **THEN** ela registra o que o dado virou contra o a priori (inclusive contra
  extrapolações), recomenda um caminho (plano A / plano B / só backbone / manter
  clearnet+modo opcional) e nomeia o ADR novo a escrever

### Requirement: Honestidade de evidência no relatório

O relatório SHALL etiquetar cada claim com sua classe de evidência e SHALL declarar
os limites não-medidos sem prometer garantias.

#### Scenario: Limite declarado aparece na conclusão

- **WHEN** a conclusão referencia correlação global-passivo / timing
- **THEN** ela a marca como fora de escopo, não medida e jamais prometida
