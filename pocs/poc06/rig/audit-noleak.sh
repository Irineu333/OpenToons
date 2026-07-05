#!/usr/bin/env bash
# poc-06 T6 — auditoria de não-vazamento no caminho de leitura (spec: "pcap prova ausência
# de fallback clearnet"). Duas frentes convergentes, como o poc-05:
#   (1) pcap: durante a leitura, 0 pacotes do HOST direto ao IP de serviço de R na VPS
#       (o app alcança R SÓ por túnel I2P, nunca por clearnet direto);
#   (2) por processo: a única conexão TCP do PID do leitor é 127.0.0.1:7656 (SAM local).
# Uso: audit-noleak.sh <VPS_IP> <R_DEST> <R_ID>
set -u
VPS_IP="${1:?IP da VPS}"; RDEST="${2:?R_DEST}"; RID="${3:?R_ID}"
# senha de sudo lida do ambiente (nunca embutida): export POC_SUDO_PW=... antes de rodar
SUDO_PW="${POC_SUDO_PW:?export POC_SUDO_PW com a senha de sudo antes de rodar}"
ROOT=/Users/aiqfome/IdeaProjects/study/OpenToons
JAVA="$(/usr/libexec/java_home -v 21)/bin/java"
CP="$ROOT/poc06/node/build/install/node/lib/*"
PCAP=/Users/aiqfome/.claude/jobs/eb0637be/tmp/t6.pcap
IFACE=$(route get default 2>/dev/null | awk '/interface:/{print $2}')
echo "interface default = $IFACE ; alvo (R na VPS) = $VPS_IP"

# pcap privilegiado: captura QUALQUER pacote de/para o IP de serviço de R durante a leitura
sudo -S tcpdump -i "$IFACE" -n -w "$PCAP" "host $VPS_IP" >/dev/null 2>&1 <<< "$SUDO_PW" &
TCPDUMP_WAIT=$!
sleep 2

echo "== leitura por I2P (fetch frio de R) =="
"$JAVA" -cp "$CP" org.opentoons.poc6.node.I2pProbeMain --mode=fetch \
  --r-dest="$RDEST" --r-id="$RID" --nick=poc6-t6 > /tmp/t6-fetch.log 2>&1 &
JPID=$!

# frente (2): enquanto o fetch roda, amostrar as conexões TCP do PID do leitor
sleep 15
echo "== conexões TCP do PID do leitor ($JPID) durante a leitura =="
lsof -nP -p "$JPID" -a -i TCP 2>/dev/null | awk 'NR==1 || /TCP/{print "  "$0}' | head -20

wait "$JPID"
grep -E "FETCH_OK|Exception" /tmp/t6-fetch.log | head -2

sleep 2
sudo -S kill "$(pgrep -f "tcpdump -i $IFACE")" 2>/dev/null <<< "$SUDO_PW"
sleep 1

echo "== veredicto pcap: pacotes HOST↔$VPS_IP durante a leitura =="
N=$(sudo -S tcpdump -r "$PCAP" -n 2>/dev/null <<< "$SUDO_PW" | grep -c "$VPS_IP")
echo "PACOTES_AO_IP_DE_R=$N (esperado 0 — leitura 100% por túnel I2P, sem fallback clearnet)"
