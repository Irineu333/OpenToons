#!/bin/bash
# OpenSSL para iOS arm64 (device) — estático, para linkar no libi2pd embarcado no iPhone.
set -euo pipefail
ROOT=/private/tmp/claude-501/-Users-aiqfome-IdeaProjects-study-OpenToons/4f389871-68a9-4054-8d83-761d2b4242ee/scratchpad/i2p-ios
OUT=$ROOT/out/openssl-ios-arm64
VER=3.5.0
cd "$ROOT"
if [ ! -f "openssl-$VER.tar.gz" ]; then
  echo "== baixando openssl $VER =="
  curl -fL -o "openssl-$VER.tar.gz" "https://github.com/openssl/openssl/releases/download/openssl-$VER/openssl-$VER.tar.gz"
fi
rm -rf "openssl-$VER"
tar xf "openssl-$VER.tar.gz"
cd "openssl-$VER"

export CROSS_TOP="$(xcrun --sdk iphoneos --show-sdk-platform-path)/Developer"
export CROSS_SDK="$(basename $(xcrun --sdk iphoneos --show-sdk-path))"
export CC=clang

echo "== Configure ios64-cross (CROSS_TOP=$CROSS_TOP CROSS_SDK=$CROSS_SDK) =="
./Configure ios64-cross no-shared no-tests no-async no-engine \
  --prefix="$OUT" -mios-version-min=13.0

echo "== make =="
make -j"$(sysctl -n hw.ncpu)" build_libs
make install_dev

echo "== OPENSSL-IOS-DONE =="
ls -la "$OUT/lib" | grep -E 'libssl|libcrypto'
lipo -info "$OUT/lib/libcrypto.a" 2>/dev/null || true
