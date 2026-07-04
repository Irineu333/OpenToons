#!/bin/zsh
# PoC poc-04 E4/S1 — malha de 4 full nodes em PROCESSOS SEPARADOS (bootstrap A +
# publicador P + R1 + R2), morte do publicador → expiry, revive → republish.
# uso: s1-mesh.sh <TramaMainKt|Libp2pMainKt> <porta-base> <logdir>
# O MESMO script para os dois backends: só o composition root (main) muda.
set -eu
MAIN=org.opentoons.poc4.node.$1
BASE=$2
LOGDIR=$3
mkdir -p "$LOGDIR"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA=/Users/aiqfome/Library/Java/JavaVirtualMachines/temurin-21.0.8/Contents/Home/bin/java
CP="$ROOT/poc04/node/build/install/node/lib/*"
JNA="-Djna.library.path=$ROOT/poc04/libp2p/nativelib"
TUNE=(--ttl=10000 --republish=2000)

pids=()
cleanup() { kill ${pids[@]} 2>/dev/null || true; }
trap cleanup EXIT

run_node() { # nome porta args...
  local name=$1; local port=$2; shift 2
  $JAVA $JNA -cp "$CP" $MAIN node --listen=$port --seed=s1-$name "${TUNE[@]}" "$@" > "$LOGDIR/$name.log" 2>&1 &
  pids+=($!)
}

resolve_count() { # bootstrap-arg
  $JAVA $JNA -cp "$CP" $MAIN client --bootstrap=$1 --mode=resolve 2>/dev/null | grep -o "RESOLVE [0-9]*" | awk '{print $2}'
}

echo "== S1 $1 =="
run_node a $BASE
sleep 3
A=$(grep -o "BOOTSTRAP_ARG=.*" "$LOGDIR/a.log" | cut -d= -f2)
echo "A=$A"

run_node p $((BASE+1)) --peer=$A --publish
run_node r1 $((BASE+2)) --peer=$A
run_node r2 $((BASE+3)) --peer=$A
sleep 6

R2=$(grep -o "BOOTSTRAP_ARG=.*" "$LOGDIR/r2.log" | cut -d= -f2)
echo "R2=$R2"

# 1) malha converge + anúncio propaga: resolve via A e via R2 (que nunca recebeu publish)
for target in "A=$A" "R2=$R2"; do
  arg=${target#*=}; who=${target%%=*}
  n=0
  for i in $(seq 1 20); do
    n=$(resolve_count $arg || echo 0); [ "${n:-0}" -ge 1 ] && break; sleep 1
  done
  echo "RESOLVE via $who: ${n:-0} provider(s)"
  [ "${n:-0}" -ge 1 ] || { echo "S1 FALHOU: anuncio nao propagou ate $who"; exit 1; }
done

# 2) download verificado via descoberta pelo R2 (o app só conhece R2)
$JAVA $JNA -cp "$CP" $MAIN client --bootstrap=$R2 --mode=fetch | tee "$LOGDIR/fetch.log" | grep -E "DESCOBERTO|VERIFICADO"
grep -q "VERIFICADO" "$LOGDIR/fetch.log" || { echo "S1 FALHOU: fetch"; exit 1; }

# 3) matar P → provider expira (ttl=10s)
PPID_=$(pgrep -f "seed=s1-p" | head -1)
kill $PPID_ && echo "P morto (pid $PPID_)"
gone=""
for i in $(seq 1 40); do
  n=$(resolve_count $A || echo 0)
  [ "${n:-0}" -eq 0 ] && { gone="em ${i}s"; break; }; sleep 1
done
[ -n "$gone" ] || { echo "S1 FALHOU: provider nao expirou"; exit 1; }
echo "EXPIRY OK $gone (via A)"

# 4) reviver P (mesma porta, mesma seed) → republish traz de volta
run_node p $((BASE+1)) --peer=$A --publish
back=""
for i in $(seq 1 30); do
  n=$(resolve_count $R2 || echo 0)
  [ "${n:-0}" -ge 1 ] && { back="em ${i}s"; break; }; sleep 1
done
[ -n "$back" ] || { echo "S1 FALHOU: provider nao voltou"; exit 1; }
echo "REPUBLISH OK $back (via R2)"

echo "== S1 $1 VERDE =="
