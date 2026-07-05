import socket, threading, time, sys
SRV_B32 = "l7j5jzugqppfzt76esaoaq7jxzqsbykwum4q5o2cfa37uqzkycta.b32.i2p"
SOCKS = ("127.0.0.1", 4447)

def echo_server():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("127.0.0.1", 6000)); s.listen(4)
    while True:
        c,_ = s.accept()
        threading.Thread(target=lambda c=c: c.sendall(b"ECHO:"+c.recv(4096)), daemon=True).start()

def socks5_connect(host, port, deadline):
    while time.time() < deadline:
        try:
            s = socket.create_connection(SOCKS, timeout=60)
            s.sendall(b"\x05\x01\x00")                      # greeting, no-auth
            if s.recv(2) != b"\x05\x00": raise IOError("no-auth recusado")
            h = host.encode()
            s.sendall(b"\x05\x01\x00\x03"+bytes([len(h)])+h+port.to_bytes(2,"big"))  # CONNECT domain (SOCKS5h)
            rep = s.recv(10)
            if len(rep) >= 2 and rep[1] == 0x00:
                return s
            s.close(); print(f"  SOCKS reply={rep[1] if len(rep)>1 else '?'} — leaseSet ainda publicando…", flush=True)
        except Exception as e:
            print(f"  retry: {e}", flush=True)
        time.sleep(5)
    raise TimeoutError("SOCKS→.b32.i2p esgotou prazo")

threading.Thread(target=echo_server, daemon=True).start()
time.sleep(1)
t0 = time.time()
print("SOCKS5 CONNECT ao server tunnel .b32.i2p (via i2pd SOCKS)…", flush=True)
s = socks5_connect(SRV_B32, 6000, t0+180)
dt = time.time()-t0
s.sendall(b"ping-libp2p-path")
resp = s.recv(4096)
assert resp == b"ECHO:ping-libp2p-path", f"resposta inesperada: {resp!r}"
print(f"SOCKS_SPIKE_OK bytes ida-e-volta pelo I2P em {dt:.1f}s (SOCKS-dial + server-tunnel-listen) resp={resp!r}")
