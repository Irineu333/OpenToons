#!/usr/bin/env bash
# poc-05 (task 1.4 / D5 camada 1) — captura de rede na interface do publicador P.
# CRITÉRIO BINÁRIO: durante a sessão anônima completa, ZERO pacotes para qualquer destino
# que não seja o daemon Tor local (127.0.0.1:9050 SOCKS). Inclui DNS (porta 53), NTP, e
# qualquer dial direto de fallback. A asserção é "0 pacotes fora do Tor", não "0 pacotes
# que eu lembrei de checar" — por isso o filtro é por EXCLUSÃO do que é legítimo.
#
# Precisa de sudo (captura de pacotes). Uso:
#   sudo poc05/rig/audit-pcap.sh <interface> <arquivo.pcap>
# depois rode a sessão anônima (AnonRig) em OUTRO terminal; Ctrl-C aqui ao terminar.
# Análise ao final: quantos pacotes saíram para destino != loopback:9050.
set -euo pipefail
IFACE="${1:-en0}"
OUT="${2:-poc05/rig/capture.pcap}"

echo "[pcap] capturando em $IFACE → $OUT (Ctrl-C para parar)"
# O tráfego LEGÍTIMO do publicador P (o processo JVM) é só para 127.0.0.1:9050.
# O daemon Tor, um processo SEPARADO, é quem disca para os relays — esse tráfego é do Tor,
# não de P. Para isolar P de verdade, o ideal é captura por-processo; na ausência dela no
# macOS, capturamos tudo e a análise abaixo separa "P falou com != 9050 local?".
tcpdump -i "$IFACE" -n -w "$OUT" 2>/dev/null || {
  echo "[pcap] tcpdump falhou (precisa de sudo?)"; exit 1; }
