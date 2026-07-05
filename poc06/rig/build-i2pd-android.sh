#!/usr/bin/env bash
# poc-06 — cross-compila o i2pd 2.60 para arm64-android e roda NO device (Moto g30).
# É o que torna o crux mobile (T3/T4) REAL: um router I2P nativo no device, usando a rede
# móvel do próprio device — sem `adb reverse`/USB no caminho de rede. Reproduz o que foi
# executado nesta rodada. Requer: NDK r28, ~/Library/Android/sdk, adb, o device conectado.
#
# Cadeia: OpenSSL 3.5.4 (static, android-arm64) + Boost 1.86 (filesystem/program_options/
# system, static, clang-android) + zlib (sysroot do NDK) → i2pd (Makefile.linux, libc++
# estático). O único patch no i2pd é forçar -std=c++20 (o Makefile usa `expr match`, que é
# GNU-only; o macOS tem BSD expr).
set -euo pipefail

NDK="${NDK:-$HOME/Library/Android/sdk/ndk/28.2.13676358}"
TC="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
API=26
WORK="${WORK:-/tmp/poc06-i2pd-android}"
DEPS="$WORK/android-deps"
mkdir -p "$WORK" "$DEPS"

# wrappers clang++/clang (o Makefile do i2pd exige que $CXX comece por "clang")
WRAP="$WORK/wrap"; mkdir -p "$WRAP"
printf '#!/bin/sh\nexec "%s/bin/aarch64-linux-android%s-clang++" "$@"\n' "$TC" "$API" > "$WRAP/clang++"
printf '#!/bin/sh\nexec "%s/bin/aarch64-linux-android%s-clang" "$@"\n' "$TC" "$API" > "$WRAP/clang"
chmod +x "$WRAP/clang++" "$WRAP/clang"
export PATH="$WRAP:$TC/bin:$PATH"

# 1) OpenSSL
cd "$WORK"
[ -d openssl-3.5.4 ] || { curl -sSL -o o.tgz https://github.com/openssl/openssl/releases/download/openssl-3.5.4/openssl-3.5.4.tar.gz; tar xzf o.tgz; }
cd openssl-3.5.4
ANDROID_NDK_ROOT="$NDK" ./Configure android-arm64 -D__ANDROID_API__=$API no-shared no-tests --prefix="$DEPS"
ANDROID_NDK_ROOT="$NDK" make -j8 build_libs && ANDROID_NDK_ROOT="$NDK" make install_dev

# 2) Boost
cd "$WORK"
[ -d boost_1_86_0 ] || { curl -sSL -o b.tgz https://archives.boost.io/release/1.86.0/source/boost_1_86_0.tar.gz; tar xzf b.tgz; }
cd boost_1_86_0
./bootstrap.sh --with-libraries=filesystem,program_options,system
cat > user-config-android.jam <<EOF
using clang : android : $TC/bin/aarch64-linux-android$API-clang++
  : <archiver>$TC/bin/llvm-ar <ranlib>$TC/bin/llvm-ranlib ;
EOF
./b2 -j8 --user-config=user-config-android.jam toolset=clang-android target-os=android \
  architecture=arm address-model=64 --with-filesystem --with-program_options --with-system \
  link=static runtime-link=static variant=release --prefix="$DEPS" install

# 3) i2pd
cd "$WORK"
[ -d i2pd ] || git clone --depth 1 --branch 2.60.0 https://github.com/PurpleI2P/i2pd.git
cd i2pd
# patch: forçar c++20 (BSD expr do macOS não tem `expr match`)
perl -0pi -e "s/ifeq \(\\\$\(shell expr match \\\$\(CXX\) 'clang'\),5\).*?\\\$\(error Compiler too old\)\nendif/NEEDED_CXXFLAGS += -std=c++20/s" Makefile.linux || true
make -j8 DEBUG=no CXX=clang++ CC=clang AR=llvm-ar \
  CXXFLAGS="-Os -Wall -Wno-unused-parameter -Wno-psabi -I$DEPS/include" \
  LDFLAGS="-static-libstdc++ -L$DEPS/lib" \
  LDLIBS="$DEPS/lib/libboost_program_options.a $DEPS/lib/libboost_filesystem.a $DEPS/lib/libboost_system.a $DEPS/lib/libssl.a $DEPS/lib/libcrypto.a -lz -ldl -latomic" \
  i2pd
"$TC/bin/llvm-strip" i2pd -o i2pd-android-arm64
file i2pd-android-arm64  # ELF aarch64 PIE, interpreter /system/bin/linker64

# 4) deploy + run no device (SAM local, reseed pela rede móvel do device)
adb push i2pd-android-arm64 /data/local/tmp/i2pd
adb shell chmod 755 /data/local/tmp/i2pd
adb shell mkdir -p /data/local/tmp/i2pd-data/certificates
adb push /opt/homebrew/opt/i2pd/share/i2pd/certificates/. /data/local/tmp/i2pd-data/certificates/
adb push "$(dirname "$0")/i2pd-android.conf" /data/local/tmp/i2pd-android.conf
adb shell 'cd /data/local/tmp && nohup ./i2pd --datadir=/data/local/tmp/i2pd-data --conf=/data/local/tmp/i2pd-android.conf --logfile=/data/local/tmp/i2pd-data/i2pd.log >/dev/null 2>&1 &'
echo "router i2pd subindo no device; SAM em 127.0.0.1:7656 (do device)."
echo "prova de rede real: adb shell ss -tnp | grep i2pd  → egress por 172.20.10.x dev wlan0"
