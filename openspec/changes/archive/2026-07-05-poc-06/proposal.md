## Why

A arquitetura documentada trata anonimato e alcançabilidade como problemas
separados: o [poc-05](../../../docs/poc05-report.md) provou um **modo anônimo
opcional** (publicador sobre Tor) mantendo leitores em clearnet, e o
[ADR-0006](../../../docs/decisions/0006-nat-and-reachability.md) resolve NAT com
endereço público manual. Surge a hipótese de que **basear a rede inteira em I2P**
colapsaria os dois problemas de uma vez — NAT transversal e anonimato "de graça" —
e simplificaria a arquitetura. A hipótese não pode ser aceita nem descartada por
raciocínio: o custo real (sobretudo no caminho de leitura do mobile) só se conhece
medindo. Este poc mede.

## What Changes

- Constrói um **instrumento** — adapter de transporte I2P (sobre SAM v3) para o
  backend Trama, bidirecional (discar e servir), reusando o seam do poc-05
  (`:api`, `:trama`, `:node`) — e o valida por um **TCK** que precisa ficar verde
  em cenário controlado **antes** de qualquer medição sobre I2P real.
- Executa uma **campanha de medição em rede real** (DEV + VPS + Android em dados
  móveis, três redes separadas), com limiares fixados **a priori**, testando os
  dois papéis do mobile: **plano A** (consumidor puro) e **plano B** (nó pleno).
- Produz um **relatório** cuja conclusão inclui, obrigatoriamente: viabilidade
  técnica, prós e contras, comparação com a arquitetura documentada, e aprendizado
  com recomendação.
- **Nada de suposição**: cada claim carrega classe de evidência (`[executado]` /
  `[dado-só]` / limite declarado); extrapolações de números do Tor (poc-05) **não**
  contam como dado de I2P.
- **Não** altera a arquitetura de produção nem os ADRs — o poc gera conhecimento
  e uma recomendação; qualquer mudança de ADR é consequência posterior.

## Capabilities

### New Capabilities

- `i2p-transport-instrument`: adapter de transporte I2P/SAM para o backend Trama
  (discar + servir), com TCK de correção (push/fetch de conteúdo assinado,
  rejeição de chave errada, Bitswap e descoberta reais sobre o stream I2P) que
  atua como portão antes de medir.
- `i2p-viability-campaign`: bancada real (rig com routers I2P, réguas aferidas) e
  campanha de testes T0–T6 sobre DEV+VPS+Android, com limiares a priori e classes
  de evidência, cobrindo cold-start, throughput, descoberta, os dois planos de
  mobile, o impacto por camada de arquitetura e a auditoria de não-vazamento.
- `poc06-report`: relatório do poc com conclusão em quatro partes obrigatórias
  (viabilidade técnica; prós e contras; comparação com a arquitetura documentada;
  aprendizado e recomendação), cada uma rastreável a testes executados.

### Modified Capabilities

<!-- Nenhuma: este poc não altera requisitos de capabilities existentes; produz
     conhecimento e uma recomendação. Alterações de ADR/arquitetura, se houver,
     são mudanças posteriores derivadas da recomendação. -->

## Impact

- **Novos módulos** (no molde do poc-05): `poc06/api`, `poc06/trama`, `poc06/node`,
  `poc06/rig`, registrados no `settings.gradle.kts`. Reuso do seam, não redesenho.
- **Dependência externa nova**: router I2P (i2pd ou I2P Java) expondo SAM v3 em cada
  host; herda o reseed do I2P como novo modo de falha.
- **Recursos de execução**: shell com permissão total, ambiente JVM, dispositivo
  Android em rede separada (dados móveis), uma VPS.
- **Entregável**: `docs/poc06-report.md`. Sem impacto em código de produção.
- **Referências cruzadas**: ADR-0001 (papéis/push), ADR-0002 (três planos),
  ADR-0005 (mobile leve), ADR-0006 (NAT), ADR-0007 (bootstrap), poc-05 (seam,
  gatilho invertido).
