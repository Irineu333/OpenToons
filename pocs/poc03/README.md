# poc03/ — libp2p de referência via bindings nativos (DESCARTÁVEL)

Módulo descartável da PoC **poc-03**. O entregável durável é [`docs/poc03-report.md`](../../docs/pocs/poc03-report.md);
este código pode ser apagado depois. Ver [openspec/changes/poc-03](../../openspec/changes/archive/2026-07-03-poc-03/proposal.md).

Compara embarcar o **libp2p de referência** no Android/KMP em duas variantes (Tier B: gerador de
binding pronto + facade fino nosso):

| Dir | Variante | Toolchain | Superfície FFI |
|---|---|---|---|
| `go-facade/` | go-libp2p | `gomobile bind` → `.aar` | `dial`/`resolve`/`getBlocks` |
| `rust-facade/` | rust-libp2p | `cargo-ndk` + UniFFI → `.so` + Kotlin | idem (paridade) |
| `net/` | — (Gradle `:pocs:poc03:net`) | Kotlin/JVM | verificação Ed25519/hash do lado do app (D7) |
| `android/` | app da variante **rust** | AGP | carrega o `.so` (2.3/3.3) |
| `android-go/` | app da variante **go** | AGP | carrega o `.aar` (2.3) |

A verificação de assinatura/hash **não cruza a fronteira FFI** (design D7): fica em Kotlin
(`net/…/ChapterVerifier.kt`), o mesmo mecanismo das três POCs. `poc01/` e `poc02/` ficam intocados
como baselines.

## Pré-requisitos do toolchain

- Go + `gomobile` (`go install golang.org/x/mobile/cmd/gomobile@latest`)
- Rust + targets Android (`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`)
- `cargo-ndk` (`cargo install cargo-ndk`) + Android **NDK** (r28c usado; `sdkmanager "ndk;28.2.13676358"`)
- `export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<versão>`

## Reproduzir os builds nativos (os artefatos são gitignored)

```bash
# E1b — rust: .so por ABI + binding Kotlin (--lib: só a cdylib, não os bins de host)
cd rust-facade
cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -o ./jniLibs build --release --lib
cargo run --bin uniffi-bindgen -- generate src/facade.udl --language kotlin --out-dir generated-kotlin
cp generated-kotlin/uniffi/facade/facade.kt ../android/src/main/kotlin/uniffi/facade/facade.kt
cp -R jniLibs/* ../android/src/main/jniLibs/

# E1a — go: .aar (o -ldflags contorna o //go:linkname do anet no Go 1.23+)
cd ../go-facade
gomobile bind -ldflags="-checklinkname=0" \
  -target=android/arm64,android/arm,android/amd64 -androidapi 26 \
  -o opentoons-gofacade.aar .

# apps (do raiz do repo)
cd ..
./gradlew :pocs:poc03:android:assembleDebug      # variante rust
./gradlew :pocs:poc03:android-go:assembleDebug    # variante go
```

### Nós de host: publicador e interop (E2/E3/E4/5.4)

```bash
# publicador rust (nó pleno: escuta, serve blocos, anuncia provider no Kademlia)
cd rust-facade && cargo build --bin publisher --release
POC3_PUBLIC_IP=<ip-público> ./target/release/publisher <porta> <obraId> [bootstrap_maddr]
#   POC3_TAMPER=block|sig corrompe o capítulo (teste de rejeição 7.3)

# publicador go (paridade)
cd ../go-facade && go build -o /tmp/go-publisher ./cmd/publisher
/tmp/go-publisher <porta> <obraId> [bootstrap_maddr]

# interop Bitswap com kubo (bônus 5.4, módulo isolado)
cd ../go-interop && go build -o /tmp/go-interop .
/tmp/go-interop <kubo_multiaddr> <cid>

# E2 local (device em outra rede): adb reverse tcp:<porta> tcp:<porta>
# E4 internet: publicador com POC3_PUBLIC_IP + port forwarding; app -e bootstrap /ip4/<pub>/tcp/<porta>/p2p/<id>
```

## Teste de carga no dispositivo (E1, 2.3/3.3)

```bash
adb install -r poc03/android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n org.opentoons.poc3.android/.MainActivity -e mode init
adb logcat -d | grep poc3        # → "NÓ INICIALIZADO SEM CRASH — peerId=12D3KooW…"

adb install -r poc03/android-go/build/outputs/apk/debug/android-go-debug.apk
adb shell am start -n org.opentoons.poc3.androidgo/.GoActivity -e mode init
adb logcat -d | grep poc3go      # → "NÓ GO INICIALIZADO SEM CRASH — peerId=…"
```

## Estado (ver o relatório para detalhes e matrizes)

- **E1 (binding) — CONCLUÍDO** nas duas variantes: cross-compile 3 ABIs + nó rodando no Moto g(30)
  API 31 sem crash. Peso: rust ~6 MB/ABI (dentro do teto), go ~29 MB/ABI (estoura).
- **E2–E5 — pendentes:** precisam de um nó pleno publicador (lado servidor: responder de blocos +
  anúncio de provider no Kademlia; os facades atuais são client-only por ADR-0005), da
  rede-bootstrap com IP público e da sessão de 30 min.
