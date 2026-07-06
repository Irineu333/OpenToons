#!/bin/bash
# Cross-compila libi2pd (i2pd 2.60) para arm64-apple-ios, linkando contra OpenSSL+Boost iOS.
# Produz out/libi2pd-ios-arm64.a (só os objetos do libi2pd; o wrapper C-ABI é compilado à parte).
set -uo pipefail
ROOT=/private/tmp/claude-501/-Users-aiqfome-IdeaProjects-study-OpenToons/4f389871-68a9-4054-8d83-761d2b4242ee/scratchpad/i2p-ios
I2PD=/private/tmp/claude-501/-Users-aiqfome-IdeaProjects-study-OpenToons/132fb966-8db5-46cf-adc6-045463e1699a/scratchpad/i2pd-src
OPENSSL=$ROOT/out/openssl-ios-arm64
BOOST=$ROOT/out/boost-ios-arm64
OBJ=$ROOT/obj-i2pd
OUT=$ROOT/out
mkdir -p "$OBJ"
SDK=$(xcrun --sdk iphoneos --show-sdk-path)

CXX="xcrun --sdk iphoneos clang++"
CXXFLAGS="-target arm64-apple-ios13.0 -isysroot $SDK -std=c++17 -O2 -DNDEBUG -DMAC_OSX \
  -fvisibility=hidden -fvisibility-inlines-hidden -Wno-everything"
INCLUDES="-I$I2PD/libi2pd -I$OPENSSL/include -I$BOOST/include"

echo "== compilando $(ls "$I2PD"/libi2pd/*.cpp | wc -l | tr -d ' ') fontes libi2pd p/ arm64-apple-ios =="
export CXX CXXFLAGS INCLUDES OBJ
compile_one() {
  local f="$1" base
  base=$(basename "$f" .cpp)
  if $CXX $CXXFLAGS $INCLUDES -c "$f" -o "$OBJ/$base.o" 2> "$OBJ/$base.err"; then
    echo "ok   $base"
  else
    echo "FAIL $base"
  fi
}
export -f compile_one
ls "$I2PD"/libi2pd/*.cpp | xargs -P "$(sysctl -n hw.ncpu)" -I{} bash -c 'compile_one "$@"' _ {}

echo "== resumo =="
FAILS=$(grep -l . "$OBJ"/*.err 2>/dev/null | wc -l | tr -d ' ')
echo "objetos: $(ls "$OBJ"/*.o 2>/dev/null | wc -l | tr -d ' ')  falhas: $FAILS"
if [ "$FAILS" != "0" ]; then
  echo "== primeiras falhas =="
  for e in "$OBJ"/*.err; do
    if [ -s "$e" ]; then echo "### $(basename "$e" .err)"; head -6 "$e"; fi
  done | head -60
  echo "== I2PD-IOS-COMPILE-INCOMPLETE =="
  exit 1
fi
ar rcs "$OUT/libi2pd-ios-arm64.a" "$OBJ"/*.o
echo "== I2PD-IOS-DONE =="
ls -la "$OUT/libi2pd-ios-arm64.a"; lipo -info "$OUT/libi2pd-ios-arm64.a"
