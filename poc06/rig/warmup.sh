#!/usr/bin/env bash
# poc-06 task 2.3/2.5 — cronômetro de warmup do router I2P (régua aferida, D4).
# Mede reseed->túnel-pronto de um router i2pd, nos dois estados exigidos pelo design:
#   FRIO  (datadir zerado: reseed + construção de túnel do zero) — deve dar > 0
#   QUENTE (netDb já cacheado: só reconstruir túnel)             — deve dar menor
# A régua é conferida contra resposta conhecida: warmup frio >> warmup quente (D4).
set -u
I2PD=/opt/homebrew/opt/i2pd/bin/i2pd
CERTS=/opt/homebrew/opt/i2pd/share/i2pd/certificates
CONF="$(dirname "$0")/i2pd-dev.conf"

start_and_time() {
  local dd="$1" label="$2"
  local pidfile="$dd/i2pd.pid" log="$dd/i2pd.log"
  local t0
  t0=$(python3 -c 'import time;print(time.time())')
  "$I2PD" --datadir="$dd" --conf="$CONF" --logfile="$log" --pidfile="$pidfile" --daemon
  while true; do
    local inb outb elapsed
    inb=$(grep -c "Inbound tunnel.*created" "$log" 2>/dev/null)
    outb=$(grep -c "Outbound tunnel.*created" "$log" 2>/dev/null)
    inb=${inb:-0}; outb=${outb:-0}
    if [ "$inb" -ge 1 ] 2>/dev/null && [ "$outb" -ge 1 ] 2>/dev/null; then
      local t_ready
      t_ready=$(python3 -c "import time;print('%.1f'%(time.time()-$t0))")
      echo "RESULT $label túnel-pronto=${t_ready}s (in=$inb out=$outb)"
      kill "$(cat "$pidfile")" 2>/dev/null; sleep 3; return 0
    fi
    elapsed=$(python3 -c "import time;print(int(time.time()-$t0))")
    if [ "$elapsed" -gt 600 ]; then
      echo "RESULT $label TIMEOUT(>600s)"; kill "$(cat "$pidfile")" 2>/dev/null; sleep 3; return 1
    fi
    sleep 2
  done
}

DD=/Users/aiqfome/IdeaProjects/study/OpenToons/poc06/rig/warmup-data

echo "== FRIO (datadir zerado, reseed do zero) =="
rm -rf "$DD"; mkdir -p "$DD"; cp -R "$CERTS" "$DD/certificates"
start_and_time "$DD" "FRIO"

echo "== QUENTE (netDb cacheado, só reconstrói túnel) =="
start_and_time "$DD" "QUENTE"

echo "DONE (régua OK se FRIO > QUENTE — o cronômetro mede o que afirma, D4)"
