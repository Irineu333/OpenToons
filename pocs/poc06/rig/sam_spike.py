#!/usr/bin/env python3
"""poc-06 task 2.1 — spike mínimo de de-riscagem da API SAM v3 (i2pd).

Prova, contra um router i2pd REAL, o ciclo completo que o adapter Kotlin vai usar:
  HELLO → SESSION CREATE (STREAM, destination transiente) → NAMING LOOKUP ME
  → STREAM ACCEPT (lado servidor) → STREAM CONNECT (lado cliente)
  → bytes nos DOIS sentidos pelo túnel I2P.

Uso: sam_spike.py [sam_host] [sam_port]
Sai com código 0 e imprime SPIKE_OK se o ciclo fechou.
"""
import socket
import sys
import threading
import time

SAM_HOST = sys.argv[1] if len(sys.argv) > 1 else "127.0.0.1"
SAM_PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 7656


def sam_line(sock, line):
    sock.sendall((line + "\n").encode())
    buf = b""
    while not buf.endswith(b"\n"):
        chunk = sock.recv(4096)
        if not chunk:
            raise RuntimeError(f"EOF esperando resposta de: {line!r} (buf={buf!r})")
        buf += chunk
    return buf.decode().strip()


def sam_connect():
    s = socket.create_connection((SAM_HOST, SAM_PORT), timeout=300)
    reply = sam_line(s, "HELLO VERSION MIN=3.1 MAX=3.3")
    assert "RESULT=OK" in reply, f"HELLO falhou: {reply}"
    return s


def parse_value(reply, key):
    for tok in reply.split():
        if tok.startswith(key + "="):
            return tok[len(key) + 1:]
    raise RuntimeError(f"{key} ausente em: {reply[:120]}…")


def create_session(nick):
    ctrl = sam_connect()
    reply = sam_line(
        ctrl,
        f"SESSION CREATE STYLE=STREAM ID={nick} DESTINATION=TRANSIENT "
        "SIGNATURE_TYPE=EdDSA_SHA512_Ed25519 inbound.quantity=2 outbound.quantity=2",
    )
    assert "RESULT=OK" in reply, f"SESSION CREATE {nick} falhou: {reply[:200]}"
    # destination PÚBLICO da sessão: NAMING LOOKUP ME no PRÓPRIO socket de controle
    # (num socket sem sessão, ME não resolve para ESTA sessão — achado do spike)
    dest = parse_value(sam_line(ctrl, "NAMING LOOKUP NAME=ME"), "VALUE")
    return ctrl, dest


def main():
    t0 = time.time()
    print(f"[{time.time()-t0:6.1f}s] criando sessão do SERVIDOR…", flush=True)
    srv_ctrl, srv_dest = create_session("spike-srv")
    print(f"[{time.time()-t0:6.1f}s] servidor pronto, dest={srv_dest[:40]}… ({len(srv_dest)} chars)", flush=True)

    print(f"[{time.time()-t0:6.1f}s] criando sessão do CLIENTE…", flush=True)
    cli_ctrl, cli_dest = create_session("spike-cli")
    print(f"[{time.time()-t0:6.1f}s] cliente pronto, dest={cli_dest[:40]}…", flush=True)

    result = {}

    def acceptor():
        acc = sam_connect()
        reply = sam_line(acc, "STREAM ACCEPT ID=spike-srv SILENT=false")
        assert "RESULT=OK" in reply, f"ACCEPT falhou: {reply}"
        # próxima linha = destination do peer que conectou
        buf = b""
        while not buf.endswith(b"\n"):
            buf += acc.recv(4096)
        peer = buf.decode().split()[0]
        result["peer_seen_by_server"] = peer
        data = acc.recv(4096)
        result["server_received"] = data
        acc.sendall(b"pong-do-servidor")
        time.sleep(2)
        acc.close()

    th = threading.Thread(target=acceptor, daemon=True)
    th.start()
    time.sleep(1)

    print(f"[{time.time()-t0:6.1f}s] STREAM CONNECT cliente→servidor…", flush=True)
    # o leaseSet do servidor leva alguns segundos para publicar → retry honesto
    conn = None
    for attempt in range(1, 13):
        conn = sam_connect()
        reply = sam_line(conn, f"STREAM CONNECT ID=spike-cli DESTINATION={srv_dest} SILENT=false")
        if "RESULT=OK" in reply:
            break
        conn.close()
        conn = None
        print(f"[{time.time()-t0:6.1f}s] tentativa {attempt}: {reply} — aguardando leaseSet…", flush=True)
        time.sleep(5)
    assert conn is not None, "CONNECT falhou após todas as tentativas"
    dial_s = time.time() - t0
    print(f"[{dial_s:6.1f}s] stream estabelecido; enviando ping…", flush=True)
    conn.sendall(b"ping-do-cliente")
    pong = conn.recv(4096)
    th.join(timeout=60)

    assert result.get("server_received") == b"ping-do-cliente", f"servidor recebeu: {result}"
    assert pong == b"pong-do-servidor", f"cliente recebeu: {pong!r}"
    assert result["peer_seen_by_server"].startswith(cli_dest[:40]), "peer visto ≠ destination do cliente"
    print(f"[{time.time()-t0:6.1f}s] SPIKE_OK bytes nos 2 sentidos; peer autenticado por destination")
    conn.close()
    srv_ctrl.close()
    cli_ctrl.close()


if __name__ == "__main__":
    main()
