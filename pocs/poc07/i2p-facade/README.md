# poc-07 i2p-facade — I2P embarcado no iOS (célula 3)

C-ABI mínimo sobre o **libi2pd** (i2pd 2.60) para Kotlin/Native, o MESMO mecanismo cinterop
já provado na libp2p. Um router I2P sobe **in-process** dentro do app iOS: reseed → tunnels →
netDB; o leitor descobre a destination `.b32` do servidor pela rede I2P (lookup de LeaseSet) e
streama o capítulo, verificando Ed25519+sha256 no device (verify fora do seam, D7).

## Artefatos (não versionados — rebuildáveis)

Os `.a` (`libpoc07i2p.a`, `deps/*.a`) e os recursos do app (`reader-ios/Resources/{certificates,netDb}`)
são build artifacts grandes, ignorados pelo git. Reconstrua na ordem:

```
scripts/build-openssl.sh   # OpenSSL 3.5 → out/openssl-ios-arm64 (libcrypto.a, libssl.a)
scripts/build-boost.sh     # Boost 1.86  → out/boost-ios-arm64 (filesystem, program_options, atomic)
scripts/build-i2pd.sh      # libi2pd     → out/libi2pd-ios-arm64.a (47 fontes, arm64-apple-ios)
./build.sh                 # wrapper poc07i2p.cpp + libi2pd → libpoc07i2p.a; copia deps/
```

> Os scripts usam caminhos absolutos de scratchpad da sessão; ajuste `ROOT`/`I2PD` no topo de
> cada um para o seu clone do i2pd (`git clone https://github.com/PurpleI2P/i2pd`) e destino.

## Servidor de campanha (I2P)

`i2pd` (stock) com um **server tunnel** (`type = server`, `host=127.0.0.1 port=6090`) apontando p/
um servidor TCP que serve o manifesto assinado + blocos (protocolo `M\n`/`B<cid>\n`/`Q\n`,
frames `[u32 BE len][payload]`). A `.b32` do túnel é a destination que o iPhone disca.

## Integração

- cinterop `poc07i2p` no `:pocs:poc07:trama` (iosArm64) → `I2pReaderProbe` em `iosArm64Main`.
- `reader-ios` linka OpenSSL/Boost/zlib/libc++ e embarca `certificates/` (reseed) + `netDb/`
  (pré-seed de routerInfos, p/ o boot ter peers conectáveis atrás de NAT).

## Nota de porte (iOS)

O `i2p::api::InitI2P` NÃO chama `SetCertsDir` → o reseed não acha os certificados. O wrapper
inlina os passos do `InitI2P` e chama `i2p::fs::SetCertsDir("")` (aponta p/ `<datadir>/certificates`).
Vários bool do i2pd são `bool_switch` (não aceitam `=valor` na CLI) — o wrapper passa só
`--datadir`/`--log`/`--logfile`/`--loglevel`.
