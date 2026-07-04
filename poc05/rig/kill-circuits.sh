#!/usr/bin/env bash
# poc-05 E5 (7.3) — mata os circuitos Tor do lado CLIENTE (o que carrega o push de P),
# preservando os HS_SERVICE de R (para R seguir alcançável). Simula a morte de circuito
# rotineira do Tor; o push retoma sem intervenção via retry de app-level. ControlPort 9051.
set -uo pipefail
PASS="${1:-poc5control}"
PORT="${2:-9051}"

# round 1: lista circuitos e coleta os IDs que NÃO são HS_SERVICE (i.e., os do publicador)
IDS=$({ printf 'AUTHENTICATE "%s"\r\n' "$PASS"; sleep 0.4
        printf 'GETINFO circuit-status\r\n'; sleep 1.0
        printf 'QUIT\r\n'; } | nc 127.0.0.1 "$PORT" 2>/dev/null \
      | grep -E '^[0-9]+ BUILT' | grep -v 'HS_SERVICE' | awk '{print $1}')

n=$(echo "$IDS" | grep -c . || true)
[ "$n" -eq 0 ] && { echo "MATOU 0 circuito(s) (nenhum circuito cliente ativo ainda)"; exit 0; }

# round 2: fecha cada um
{ printf 'AUTHENTICATE "%s"\r\n' "$PASS"
  for id in $IDS; do printf 'CLOSECIRCUIT %s\r\n' "$id"; done
  sleep 0.5; printf 'QUIT\r\n'; } | nc 127.0.0.1 "$PORT" >/dev/null 2>&1
echo "MATOU $n circuito(s) do lado cliente: $(echo $IDS | tr '\n' ' ')"
