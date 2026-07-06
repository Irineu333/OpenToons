# kmp-p2p-spi Specification

## Purpose
TBD - created by archiving change poc-07. Update Purpose after archive.
## Requirements
### Requirement: SPI de rede comum em commonMain, sem java.*

O instrumento SHALL prover uma SPI (`P2pBackend`/`FullNode`/`Blockstore`/
`FrameTransport`) em `commonMain` que compile para JVM/Android **e** Kotlin/Native
(`iosArm64`), sem qualquer dependência de `java.*` na superfície da interface.

#### Scenario: A SPI compila para o alvo iOS

- **WHEN** o módulo `api` é compilado para `iosArm64`
- **THEN** a compilação conclui e nenhum tipo da SPI referencia `java.*`
  (`Closeable`/IO trocados por `kotlinx.io` ou `expect/actual`)

#### Scenario: Verify fora do seam, neutro entre backends

- **WHEN** um capítulo é baixado por qualquer backend
- **THEN** o `ChapterVerifier` (Ed25519 + sha256) roda em Kotlin/Native, é o MESMO
  código para todos os backends, e um manifesto adulterado é rejeitado antes de
  qualquer medição de tempo

### Requirement: Trama portada para Kotlin/Native

O backend Trama (Noise XX, RPC de frames, membership) SHALL rodar em Kotlin/Native com
código real (sem simulação), com crypto e socket atrás de seams `expect/actual` ou
libs cross-platform decididos por spike medido no alvo `iosArm64`.

#### Scenario: Handshake Noise XX real no Native

- **WHEN** dois nós completam o handshake Noise XX, um deles em Kotlin/Native
- **THEN** o canal seguro é estabelecido com primitivas reais (X25519, ChaCha20Poly1305,
  HMAC-SHA256) e frames autenticados trafegam nos dois sentidos

#### Scenario: Dial TCP real do iPhone em rede móvel

- **WHEN** o nó Kotlin/Native no iPhone (dados móveis) disca o IP público da VPS
- **THEN** a conexão é estabelecida e bytes trafegam ponta a ponta, sem loopback nem
  simulador (device físico, rede real)

### Requirement: Segundo backend (libp2p) trocável no código, mesma SPI

O instrumento SHALL prover um segundo backend (rust-libp2p via cinterop C-ABI ao `.a`
em `aarch64-apple-ios`) que satisfaça a MESMA SPI, selecionável por código
(factory/DI), coexistindo com a Trama no mesmo binário iOS.

#### Scenario: Troca de backend por uma linha de código

- **WHEN** a factory seleciona `libp2p` em vez de `trama`
- **THEN** o mesmo caminho de aplicação roda sobre o outro backend sem branch de app e
  sem recompilar por build-variant

#### Scenario: Binding cinterop real ao rust

- **WHEN** o app iOS chama a SPI resolvida para o backend libp2p
- **THEN** a chamada atravessa o cinterop C-ABI até o `.a` rust e retorna, com código
  real (não stub)

### Requirement: TCK de correção como portão, por implementação, no alvo iOS

O instrumento SHALL passar o TCK de correção (do poc-04) compilado para Kotlin/Native
contra CADA backend **antes** de qualquer medição de campanha. Enquanto o TCK não
estiver verde para uma implementação, nenhum número dela SHALL ser considerado válido.

#### Scenario: Download verificado sobre a SPI no alvo iOS

- **WHEN** o TCK roda no alvo iOS contra um backend (resolve transitivo, download
  verificado, bloco adulterado → mismatch, chave errada → assinatura inválida)
- **THEN** todos os cenários passam verdes

#### Scenario: Portão bloqueia medição com TCK vermelho

- **WHEN** qualquer cenário do TCK falha para uma implementação
- **THEN** a campanha daquela célula não avança e a falha é corrigida antes de coletar
  números

