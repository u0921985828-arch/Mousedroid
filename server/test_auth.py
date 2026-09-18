"""Extremo a extremo: apreton v2, sesion cifrada, vale de un solo uso y DoS."""
import importlib.util, socket, sys, threading, time

sys.path.insert(0, "/tmp/claude-0/-home-user-Monopoly/88e577e9-1d76-55c5-b78c-f3573b1c0c45/scratchpad/stub")
import pynput.mouse as ms

spec = importlib.util.spec_from_file_location("srv", "/home/user/Monopoly/server/server.py")
srv = importlib.util.module_from_spec(spec); spec.loader.exec_module(srv)
srv.CODE = "ABCD1234EFGH"
srv.KDF_VUELTAS = 2000          # la prueba no mide el KDF, solo que coincide
fallos = []

def ck(n, c):
    print(("  ok  " if c else "  FALLO  "), n)
    if not c: fallos.append(n)

def cliente(sock, secreto):
    """Lo que hace Pairing.handshake en Kotlin, escrito aparte a proposito."""
    f = sock.makefile("rb")
    p = f.readline().decode().strip().split(" ")
    if p[0] != "U2" or len(p[1]) != 32:
        return None, f
    nS, nC = p[1], "b" * 32
    k = srv.clave(secreto)
    sock.sendall(("A %s %s\n" % (srv.mac(k, "S:%s:%s" % (nS, nC)).hex(), nC)).encode())
    r = f.readline().decode().strip()
    if not r.startswith("B ") or r[2:] != srv.mac(k, "C:%s:%s" % (nC, nS)).hex():
        return None, f
    base = srv.mac(k, "sess:%s:%s" % (nS, nC))
    return (srv.Sobre(srv.mac(base, "c2s-enc"), srv.mac(base, "c2s-mac")),
            srv.Sobre(srv.mac(base, "s2c-enc"), srv.mac(base, "s2c-mac"))), f

def sesion(secreto, ordenes=(), crudo=None, espera=0.5):
    ls = socket.socket(); ls.bind(("127.0.0.1", 0)); ls.listen(1)
    a = socket.create_connection(ls.getsockname()); b, _ = ls.accept()
    threading.Thread(target=srv.handle, args=(b, ("9.9.9.9", 0), True), daemon=True).start()
    sob, f = cliente(a, secreto)
    recibido = None
    if sob:
        salida, entrada = sob
        if crudo is not None:
            a.sendall(crudo)
        else:
            for o in ordenes:
                a.sendall(salida.cerrar(o).encode())
    time.sleep(espera)
    try: a.close()
    except Exception: pass
    ls.close()
    return sob, f

# 1. camino bueno
ms.LOG.clear()
sob, _ = sesion(srv.CODE, ["M10,5", "Chola"])
ck("apreton v2 mutuo", sob is not None)
ck("las ordenes llegan descifradas", ("move", 10, 5) in ms.LOG)

# 2. codigo malo
ms.LOG.clear()
sob, _ = sesion("ZZZZZZZZZZZZ", ["M99,99"], espera=1.0)
ck("codigo malo: el PC no se autentica", sob is None)
ck("codigo malo: no se ejecuta nada", ms.LOG == [])

# 3. nada en claro: el protocolo de antes ya no cuela
ms.LOG.clear()
sesion(srv.CODE, crudo=b"M50,50\n", espera=0.5)
ck("linea en claro rechazada", ms.LOG == [])

# 4. repeticion de una linea valida
ms.LOG.clear()
ls = socket.socket(); ls.bind(("127.0.0.1", 0)); ls.listen(1)
a = socket.create_connection(ls.getsockname()); b, _ = ls.accept()
threading.Thread(target=srv.handle, args=(b, ("9.9.9.9", 0), True), daemon=True).start()
sob, _ = cliente(a, srv.CODE)
linea = sob[0].cerrar("M3,3").encode()
a.sendall(linea); time.sleep(0.3)
a.sendall(linea); time.sleep(0.4)
a.close(); ls.close()
ck("repetir una linea no repite la orden", ms.LOG.count(("move", 3, 3)) == 1)

# 5. vale de un solo uso: entrega el codigo y luego no vale
v = srv.nuevo_vale()
ms.LOG.clear()
sob, f = sesion(v, ["M1,1"], espera=0.5)
ck("el vale abre la puerta", sob is not None)
sob2, _ = sesion(v, ["M2,2"], espera=0.7)
ck("el vale no sirve dos veces", sob2 is None)

# 6. sockets callados no bloquean al movil de verdad
mudos = []
for _ in range(srv.MAX_CONEXIONES + 2):
    ls = socket.socket(); ls.bind(("127.0.0.1", 0)); ls.listen(1)
    a = socket.create_connection(ls.getsockname()); b, _ = ls.accept()
    threading.Thread(target=srv.handle, args=(b, ("8.8.8.8", 0), False), daemon=True).start()
    mudos.append((ls, a))
time.sleep(0.3)
ms.LOG.clear()
sob, _ = sesion(srv.CODE, ["M4,4"])
ck("callados no dejan fuera al movil", sob is not None and ("move", 4, 4) in ms.LOG)
for ls, a in mudos:
    a.close(); ls.close()

# 7. loopback no se puede bloquear
srv.FALLOS.clear()
for _ in range(8):
    srv.anotar_fallo("127.0.0.1")
ck("127.0.0.1 nunca se bloquea", not srv.bloqueada("127.0.0.1"))
for _ in range(6):
    srv.anotar_fallo("9.9.9.9")
ck("una IP de fuera si se bloquea", srv.bloqueada("9.9.9.9"))

print()
print("FALLOS:", fallos if fallos else "ninguno")
sys.exit(1 if fallos else 0)
