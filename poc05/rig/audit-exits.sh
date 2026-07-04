#!/usr/bin/env bash
# poc-05 (task 1.4 / D5 camada 3) — a asserção MAIS FORTE: todo IP que R/B viram nas
# conexões atribuíveis a P (a) != IP real de P e (b) pertence à lista de EXIT NODES do
# consenso Tor (conferível contra o Onionoo). Não é só "não vi o IP de P", é "só vi exits
# conhecidos". No caminho ONION não há exit — R registra a conexão vinda do onion service
# sem IP de origem discável (o critério vira "R não loga IP de origem", provado pelo
# `CONN noise:/127.0.0.1:…` do AnonRig). Este script serve o caminho EXIT (dial a IP público).
#
# Uso: poc05/rig/audit-exits.sh <ip-observado-por-R> [<ip-real-de-P>]
#   consulta o Onionoo pelo IP e diz se é um relay com a flag Exit.
set -euo pipefail
IP="${1:?uso: audit-exits.sh <ip-observado> [ip-real-de-P]}"
REAL_P="${2:-}"

if [[ -n "$REAL_P" && "$IP" == "$REAL_P" ]]; then
  echo "❌ VAZAMENTO: o IP observado por R É o IP real de P ($IP)"; exit 1
fi

echo "[exits] consultando o consenso Tor (Onionoo) por $IP …"
RESP=$(curl -s --max-time 15 "https://onionoo.torproject.org/details?search=$IP")
# um relay com a flag Exit tem "flags":[...,"Exit",...]; procuramos por isso no bloco do IP
if echo "$RESP" | tr -d '[:space:]' | grep -q '"relays":\[\]'; then
  echo "❌ $IP não é um relay conhecido do consenso — NÃO é exit (vazamento ou IP fora do Tor)"
  exit 1
fi
if echo "$RESP" | grep -q '"Exit"'; then
  echo "✅ $IP é um EXIT node do consenso Tor — compatível com tráfego saído de P via Tor"
  exit 0
fi
echo "⚠ $IP é relay mas sem flag Exit visível — inspecionar manualmente:"
echo "$RESP" | head -c 400
exit 1
