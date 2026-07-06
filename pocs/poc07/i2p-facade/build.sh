#!/bin/bash
# Compila o wrapper C-ABI poc07i2p.cpp p/ arm64-apple-ios e combina com o libi2pd-ios em
# libpoc07i2p.a (o que o cinterop do device linka). Dep libs (openssl/boost) linkadas via .def.
set -euo pipefail
FACADE=/Users/aiqfome/IdeaProjects/study/OpenToons/pocs/poc07/i2p-facade
I2POUT=/private/tmp/claude-501/-Users-aiqfome-IdeaProjects-study-OpenToons/4f389871-68a9-4054-8d83-761d2b4242ee/scratchpad/i2p-ios/out
I2PD=/private/tmp/claude-501/-Users-aiqfome-IdeaProjects-study-OpenToons/132fb966-8db5-46cf-adc6-045463e1699a/scratchpad/i2pd-src
OPENSSL=$I2POUT/openssl-ios-arm64
BOOST=$I2POUT/boost-ios-arm64
SDK=$(xcrun --sdk iphoneos --show-sdk-path)

CXX="xcrun --sdk iphoneos clang++"
CXXFLAGS="-target arm64-apple-ios13.0 -isysroot $SDK -std=c++17 -O2 -DNDEBUG -DMAC_OSX \
  -fvisibility=hidden -fvisibility-inlines-hidden -Wno-everything"
INCLUDES="-I$I2PD/libi2pd -I$OPENSSL/include -I$BOOST/include"

echo "== compilando wrapper poc07i2p.cpp =="
$CXX $CXXFLAGS $INCLUDES -c "$FACADE/poc07i2p.cpp" -o "$FACADE/poc07i2p.o"

echo "== combinando wrapper + libi2pd → libpoc07i2p.a =="
libtool -static -o "$FACADE/libpoc07i2p.a" "$FACADE/poc07i2p.o" "$I2POUT/libi2pd-ios-arm64.a"

echo "== POC07I2P-WRAPPER-DONE =="
ls -la "$FACADE/libpoc07i2p.a"; lipo -info "$FACADE/libpoc07i2p.a"
nm "$FACADE/libpoc07i2p.a" 2>/dev/null | grep -E '_poc07_i2p_(start|connect|recv|send)' | head
