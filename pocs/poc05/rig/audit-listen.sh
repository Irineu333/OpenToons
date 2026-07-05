#!/usr/bin/env bash
# poc-05 (task 1.4 / D5 camada 2) — 0 sockets de ESCUTA não-loopback no publicador P.
# P se comporta como o client do ADR-0005: só conexões de SAÍDA, nunca escuta. Um socket
# de escuta em interface pública seria uma porta discável — exatamente o que o modo anônimo
# nega. Este script lista os sockets de escuta do processo P (por PID) e falha se algum
# estiver ligado a endereço != loopback.
#
# Uso: poc05/rig/audit-listen.sh <PID-do-publicador>
set -euo pipefail
PID="${1:?uso: audit-listen.sh <PID>}"

echo "[listen] sockets de escuta do PID $PID:"
LISTEN=$(lsof -nP -a -p "$PID" -iTCP -sTCP:LISTEN 2>/dev/null || true)
if [[ -z "$LISTEN" ]]; then
  echo "  (nenhum) — ✅ P não escuta em porta alguma"
  exit 0
fi
echo "$LISTEN"
# algum listen em endereço não-loopback (não 127.0.0.1 nem [::1] nem localhost)?
NONLOOP=$(echo "$LISTEN" | awk 'NR>1 {print $9}' | grep -vE '127\.0\.0\.1|\[::1\]|localhost' || true)
if [[ -n "$NONLOOP" ]]; then
  echo "❌ VAZAMENTO: P escuta em endereço não-loopback:"; echo "$NONLOOP"; exit 1
fi
echo "✅ só loopback (ou nada) — sem porta discável"
