#!/bin/zsh
# PoC poc-04 E4/S2-S3-S4 — rig público: bootstrap A + publicador P (malha) e dois
# publicadores standalone de rejeição (T=bloco corrompido, W=chave errada), todos
# anunciando o IP público (port forwarding 4000-4999, ADR-0006).
# uso: rig-public.sh <TramaMainKt|Libp2pMainKt> <porta-base> <logdir> [ip-publico]
set -eu
MAIN=org.opentoons.poc4.node.$1
BASE=$2
LOGDIR=$3
PUB=${4:-177.203.17.5}
mkdir -p "$LOGDIR"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
JAVA=/Users/aiqfome/Library/Java/JavaVirtualMachines/temurin-21.0.8/Contents/Home/bin/java
CP="$ROOT/poc04/node/build/install/node/lib/*"
JNA="-Djna.library.path=$ROOT/poc04/libp2p/nativelib"

run_node() { # nome porta args...
  local name=$1; local port=$2; shift 2
  $JAVA $JNA -cp "$CP" $MAIN node --listen=$port --public=$PUB:$port --seed=rig-$name "$@" \
    > "$LOGDIR/$name.log" 2>&1 &
  echo $! >> "$LOGDIR/pids"
}

: > "$LOGDIR/pids"
run_node a $BASE
sleep 3
ALOCAL=127.0.0.1:$BASE:$(grep -o "id=[0-9a-f]*" "$LOGDIR/a.log" | cut -d= -f2)
run_node p $((BASE+1)) --peer=$ALOCAL --publish
run_node t $((BASE+2)) --publish --tamper
run_node w $((BASE+3)) --publish --wrongkey
sleep 4

for n in a p t w; do
  id=$(grep -o "id=[0-9a-f]*" "$LOGDIR/$n.log" | cut -d= -f2)
  port=$(grep -o "port=[0-9]*" "$LOGDIR/$n.log" | cut -d= -f2)
  echo "$n PUBLIC_ARG=$PUB:$port:$id"
done
echo "rig $1 no ar (logs em $LOGDIR; kill \$(cat $LOGDIR/pids) para derrubar)"
