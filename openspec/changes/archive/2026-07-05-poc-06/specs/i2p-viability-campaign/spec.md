## ADDED Requirements

### Requirement: Bancada real com réguas aferidas

A campanha SHALL montar routers I2P (SAM v3) em DEV, VPS e Android, provar
alcançabilidade mútua por destination, e **aferir cada instrumento de medida**
contra resposta conhecida antes de coletar qualquer número de viabilidade.

#### Scenario: Alcançabilidade real entre hosts

- **WHEN** cada host publica seu leaseSet
- **THEN** cada destination é discável pelos outros por dentro de túneis I2P reais

#### Scenario: Régua conferida nos dois sentidos

- **WHEN** o cronômetro de warmup roda num router quente e depois num router
  morto-e-revivido
- **THEN** ele reporta ~0 no quente e >0 no frio, provando que mede o que afirma

### Requirement: Medição em rede real, código real, a frio

A campanha SHALL medir sobre a topologia real de três redes separadas
(P=DEV, R=VPS, M=Android em dados móveis), com código real (Bitswap, gossip/DHT,
manifesto assinado sobre stream I2P), e as medições de leitura do mobile SHALL ser
feitas **a frio** (router recém-reseedado / app morto), nunca em rig quente.

#### Scenario: Caminho de transferência sem co-localização

- **WHEN** P publica, R replica e M lê
- **THEN** as três pontas estão em redes distintas e nenhuma transferência ocorre
  em loopback

#### Scenario: Leitura do mobile medida a frio

- **WHEN** o Moto g30 abre um capítulo com o router I2P frio
- **THEN** são registrados warmup, tempo-até-primeiro-byte e throughput do caminho
  frio, não do quente

### Requirement: Limiares a priori e classes de evidência

A campanha SHALL fixar D0, perguntas e limiares **antes** de qualquer medição, e
cada claim SHALL carregar classe de evidência (`[executado]`, `[dado-só]` ou limite
declarado). Extrapolações de números do Tor (poc-05) SHALL NOT contar como dado de
I2P.

#### Scenario: Limiar cravado antes do dado

- **WHEN** um teste produz um número
- **THEN** o limiar contra o qual ele é julgado já estava fixado antes da medição

#### Scenario: Limite não-medido é declarado, não afirmado

- **WHEN** uma propriedade não é validável no poc (ex.: correlação global-passivo)
- **THEN** ela é registrada como limite declarado, sem alegar segurança nem medi-la

### Requirement: Cobertura T0–T6 dos dois planos de mobile

A campanha SHALL executar os testes T0 (warmup/alcançabilidade), T1 (backbone),
T2 (descoberta gossip×DHT sobre I2P), T3 (leitura plano A), T4 (leitura plano B com
custo de bateria/uptime e frescor de provider), T5 (impacto por camada de
arquitetura) e T6 (auditoria de não-vazamento), cada um `[executado]`.

#### Scenario: Crux mede os dois papéis do mobile

- **WHEN** T3 (consumidor puro) e T4 (nó pleno) rodam no device real
- **THEN** cada um reporta o cold-start de leitura contra o limiar, e T4 reporta
  também bateria/uptime e a poluição de provider records sob intermitência

#### Scenario: Impacto por camada exercido, não raciocinado

- **WHEN** T5 exercita NAT, bootstrap, Bitswap/CID e DHT-sobre-túnel com código real
- **THEN** cada camada é classificada (subsumida / muda de forma / sobrevive / mais
  cara) por observação executada, não por argumento

#### Scenario: Auditoria de não-vazamento no caminho de leitura

- **WHEN** T6 captura pcap na interface real durante a leitura
- **THEN** confirma-se ausência de fallback clearnet no caminho de leitura
