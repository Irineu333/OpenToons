#!/bin/bash
# Boost para iOS arm64 (device) — libs estáticas que o libi2pd linka: filesystem, program_options, atomic.
set -euo pipefail
ROOT=/private/tmp/claude-501/-Users-aiqfome-IdeaProjects-study-OpenToons/4f389871-68a9-4054-8d83-761d2b4242ee/scratchpad/i2p-ios
OUT=$ROOT/out/boost-ios-arm64
VER=1.86.0
VUS=1_86_0
cd "$ROOT"
if [ ! -f "boost_$VUS.tar.bz2" ]; then
  echo "== baixando boost $VER =="
  curl -fL -o "boost_$VUS.tar.bz2" "https://archives.boost.io/release/$VER/source/boost_$VUS.tar.bz2"
fi
rm -rf "boost_$VUS"
tar xf "boost_$VUS.tar.bz2"
cd "boost_$VUS"

SDK=$(xcrun --sdk iphoneos --show-sdk-path)
echo "== bootstrap b2 (host) =="
./bootstrap.sh --with-libraries=filesystem,program_options,atomic --with-toolset=clang >/dev/null 2>&1

cat > user-config.jam <<EOF
using darwin : iphone
  : xcrun clang++ -arch arm64 -isysroot $SDK -mios-version-min=13.0 -std=c++17 -fvisibility=hidden -fvisibility-inlines-hidden
  : <striper> <root>$(xcrun --sdk iphoneos --show-sdk-platform-path)/Developer
  : <architecture>arm <target-os>iphone
  ;
EOF

echo "== b2 build (filesystem, program_options, atomic) =="
./b2 -j"$(sysctl -n hw.ncpu)" --user-config=user-config.jam \
  toolset=darwin-iphone \
  architecture=arm target-os=iphone \
  link=static variant=release threading=multi \
  --with-filesystem --with-program_options --with-atomic \
  --prefix="$OUT" --build-dir="$ROOT/boost-build-ios" \
  cxxflags="-arch arm64 -isysroot $SDK -mios-version-min=13.0 -std=c++17" \
  install 2>&1 | tail -30

echo "== BOOST-IOS-DONE =="
ls -la "$OUT/lib" 2>/dev/null | grep -E 'boost'
for L in "$OUT"/lib/libboost_*.a; do echo "$L:"; lipo -info "$L" 2>/dev/null; done
