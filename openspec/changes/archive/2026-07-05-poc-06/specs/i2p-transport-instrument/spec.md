## ADDED Requirements

### Requirement: Transporte I2P/SAM bidirecional no backend Trama

O instrumento SHALL prover um adapter de transporte I2P para o backend Trama que
fale SAM v3 a um router I2P local, reusando o seam `P2pBackend` do poc-05, com
capacidade de **discar** (STREAM CONNECT) e **servir** (STREAM ACCEPT) por
destination — sem branch de app entre desktop e Android.

#### Scenario: Discar e transmitir por destination

- **WHEN** um nó abre uma sessão SAM (session = destination) e disca a destination
  de outro nó
- **THEN** um stream bidirecional é estabelecido através de túneis I2P reais e
  bytes trafegam nos dois sentidos

#### Scenario: Servir sem depender de IP público

- **WHEN** um nó atrás de NAT publica seu leaseSet e aceita conexões de entrada
  (STREAM ACCEPT)
- **THEN** ele é discável por sua destination sem port-forward nem IP público
  configurado

#### Scenario: Mesmo código em desktop e Android

- **WHEN** o adapter é cross-compilado para Android (Kotlin/JVM) falando SAM ao
  router local
- **THEN** um grep no código de app confirma **zero** branch de transporte entre
  desktop e mobile

### Requirement: TCK de correção como portão antes de medir

O instrumento SHALL passar um TCK de correção em cenário controlado (loopback/host
único) **antes** de qualquer medição sobre I2P real. Enquanto o TCK não estiver
verde, nenhum número da campanha SHALL ser considerado válido.

#### Scenario: Push e fetch de conteúdo assinado

- **WHEN** um publicador empurra um manifesto assinado (Ed25519) mais blocos e um
  leitor baixa
- **THEN** o conteúdo é verificado byte a byte (ex.: 786432 bytes íntegros) e a
  assinatura confere

#### Scenario: Rejeição de chave errada antes de gravar

- **WHEN** um push chega assinado por chave não autorizada pela `PushPolicy`
- **THEN** o receptor rejeita **antes** de gravar e o conteúdo não é persistido

#### Scenario: Bitswap e descoberta reais sobre o stream I2P

- **WHEN** blocos são trocados via Bitswap e providers são descobertos via
  gossip/DHT
- **THEN** ambos operam sobre o stream I2P com código real (não stub) e completam
  o ciclo

#### Scenario: Portão bloqueia medição com TCK vermelho

- **WHEN** qualquer cenário do TCK falha
- **THEN** a campanha de medição não avança e a falha é corrigida antes de coletar
  números
