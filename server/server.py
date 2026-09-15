#!/usr/bin/env python3
"""
USB Mouse - servidor PC (modo automatico).

Vigila el puerto USB: en cuanto detecta el movil por adb, monta el tunel
(`adb reverse`), despierta la pantalla y abre la app. La app se reconecta
sola. No hay que tocar nada.

Protocolo (lineas ASCII terminadas en \n):
    M<dx>,<dy>   mover cursor (relativo, float)
    S<dx>,<dy>   scroll en muescas (float)
    C<l|r|m>     click
    D<l|r|m>     boton pulsado
    U<l|r|m>     boton soltado
    P            ping / keepalive
"""

import argparse
import os
import socket
import subprocess
import sys
import threading
import time

try:
    from pynput.mouse import Button, Controller
except ImportError:
    sys.exit("Falta pynput.  Instala con:  pip install pynput")

BTN = {"l": Button.left, "r": Button.right, "m": Button.middle}
PACKAGE = "com.eddie.usbmouse"
DISCOVERY_PORT = 8778
NO_WINDOW = 0x08000000 if os.name == "nt" else 0

LOG_LOCK = threading.Lock()
LOG_FILE = None


def log(*parts):
    msg = " ".join(str(p) for p in parts)
    line = time.strftime("[%H:%M:%S] ") + msg
    with LOG_LOCK:
        if LOG_FILE:
            try:
                with open(LOG_FILE, "a", encoding="utf-8") as f:
                    f.write(line + "\n")
            except Exception:
                pass
        try:
            print(line, flush=True)
        except Exception:
            pass


# ---------------------------------------------------------------- cursor

class Cursor:
    """Acumula restos fraccionarios para que los movimientos lentos no se pierdan."""

    def __init__(self):
        self.m = Controller()
        self.rx = self.ry = 0.0
        self.sx = self.sy = 0.0
        self.held = set()

    def move(self, dx, dy):
        self.rx += dx
        self.ry += dy
        ix, iy = int(self.rx), int(self.ry)
        self.rx -= ix
        self.ry -= iy
        if ix or iy:
            self.m.move(ix, iy)

    def scroll(self, dx, dy):
        self.sx += dx
        self.sy += dy
        ix, iy = int(self.sx), int(self.sy)
        self.sx -= ix
        self.sy -= iy
        if ix or iy:
            self.m.scroll(ix, iy)

    def click(self, b):
        btn = BTN.get(b)
        if btn:
            self.m.click(btn, 1)

    def press(self, b):
        btn = BTN.get(b)
        if btn:
            self.m.press(btn)
            self.held.add(btn)

    def release(self, b):
        btn = BTN.get(b)
        if btn:
            self.m.release(btn)
            self.held.discard(btn)

    def release_all(self):
        for btn in list(self.held):
            try:
                self.m.release(btn)
            except Exception:
                pass
        self.held.clear()


def handle(conn, addr, verbose=False):
    cur = Cursor()
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    log("[+] Movil conectado", addr[0])
    try:
        with conn.makefile("rb") as f:
            for raw in f:
                line = raw.strip().decode("ascii", "ignore")
                if not line:
                    continue
                op, arg = line[0], line[1:]
                try:
                    if op == "M":
                        x, y = arg.split(",")
                        cur.move(float(x), float(y))
                    elif op == "S":
                        x, y = arg.split(",")
                        cur.scroll(float(x), float(y))
                    elif op == "C":
                        cur.click(arg)
                    elif op == "D":
                        cur.press(arg)
                    elif op == "U":
                        cur.release(arg)
                    elif op == "P":
                        pass
                    elif verbose:
                        log("    ? comando desconocido:", line)
                except Exception as e:
                    if verbose:
                        log("    ! error en", line, "->", e)
    except (ConnectionResetError, OSError):
        pass
    finally:
        cur.release_all()
        try:
            conn.close()
        except Exception:
            pass
        log("[-] Movil desconectado")


# ---------------------------------------------------------------- adb

def adb(exe, *args, timeout=25):
    try:
        return subprocess.run(
            [exe, *args], capture_output=True, text=True,
            timeout=timeout, creationflags=NO_WINDOW,
        )
    except FileNotFoundError:
        return None
    except subprocess.TimeoutExpired:
        return None


def adb_devices(exe):
    """Devuelve {serial: estado}."""
    r = adb(exe, "devices")
    if r is None or r.returncode != 0:
        return None
    out = {}
    for line in r.stdout.splitlines()[1:]:
        line = line.strip()
        if not line or "\t" not in line:
            continue
        serial, state = line.split("\t", 1)
        out[serial.strip()] = state.strip()
    return out


def prepare(exe, serial, port, launch, wake):
    """Monta el tunel y, si toca, abre la app en ese movil."""
    r = adb(exe, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}")
    if r is None or r.returncode != 0:
        log("    ! adb reverse fallo:", (r.stderr or r.stdout).strip() if r else "adb no responde")
        return False
    log("    tunel USB listo (tcp:%d)" % port)
    if wake:
        adb(exe, "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if launch:
        r = adb(exe, "-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity")
        if r is not None and r.returncode == 0 and "Error" not in (r.stderr or ""):
            log("    app abierta en el movil")
        else:
            log("    (la app no esta instalada todavia en ese movil)")
    return True


def watcher(exe, port, launch, wake, interval=2.0):
    """Bucle eterno: detecta enchufe/desenchufe y reconfigura solo."""
    ready = set()
    warned_adb = False
    warned_auth = set()
    adb(exe, "start-server")
    while True:
        devs = adb_devices(exe)
        if devs is None:
            if not warned_adb:
                log("[!] No encuentro 'adb'. Instala Platform-Tools o usa --adb C:\\ruta\\adb.exe")
                warned_adb = True
            time.sleep(5)
            continue
        warned_adb = False

        online = {s for s, st in devs.items() if st == "device"}
        for s, st in devs.items():
            if st == "unauthorized" and s not in warned_auth:
                log("[!] Movil", s, "sin autorizar: acepta el dialogo de depuracion USB")
                log("    y marca 'Permitir siempre desde este equipo'.")
                warned_auth.add(s)
        warned_auth &= set(devs)

        for s in online - ready:
            log("[+] Movil detectado:", s)
            if prepare(exe, s, port, launch, wake):
                ready.add(s)
        for s in ready - online:
            log("[-] Movil desenchufado:", s)
        ready &= online
        time.sleep(interval)


def discovery_responder(port, disc_port=DISCOVERY_PORT):
    """Responde a los sondeos del movil para que encuentre la IP del PC solo."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("0.0.0.0", disc_port))
    except OSError as e:
        log("[!] Descubrimiento desactivado:", e)
        return
    log("Descubrimiento automatico activo (UDP %d)" % disc_port)
    while True:
        try:
            data, addr = s.recvfrom(64)
            if data.strip().startswith(b"USBMOUSE?"):
                s.sendto(b"USBMOUSE:%d" % port, addr)
        except Exception:
            time.sleep(0.5)


def local_ips():
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips


# ---------------------------------------------------------------- main

def main():
    global LOG_FILE
    p = argparse.ArgumentParser(description="Servidor PC de USB Mouse")
    p.add_argument("--port", type=int, default=8777)
    p.add_argument("--adb", default="adb", help="ruta al ejecutable adb")
    p.add_argument("--auto", action="store_true", default=True,
                   help="vigilar el USB y configurar solo (por defecto)")
    p.add_argument("--no-auto", dest="auto", action="store_false")
    p.add_argument("--no-launch", dest="launch", action="store_false", default=True,
                   help="no abrir la app automaticamente en el movil")
    p.add_argument("--no-wake", dest="wake", action="store_false", default=True,
                   help="no encender la pantalla al enchufar")
    p.add_argument("--lan", action="store_true",
                   help="escuchar en todas las interfaces (anclaje USB o Wi-Fi, sin adb)")
    p.add_argument("--log", nargs="?", const="usbmouse.log", default=None,
                   help="volcar la salida a un fichero (modo silencioso)")
    p.add_argument("-v", "--verbose", action="store_true")
    a = p.parse_args()

    if a.log:
        LOG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), a.log)

    host = "0.0.0.0" if a.lan else "127.0.0.1"
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((host, a.port))
    except OSError as e:
        log("[!] No puedo abrir el puerto", a.port, "->", e, "(ya hay otro servidor corriendo?)")
        return
    srv.listen(2)

    log("=" * 46)
    log("USB Mouse - escuchando en %s:%d" % (host, a.port))
    if a.lan:
        log("IPs de este PC:", ", ".join(local_ips()) or "(desconocidas)")
        log("En anclaje USB suele ser 192.168.42.x")
    threading.Thread(target=discovery_responder, args=(a.port,), daemon=True).start()
    if a.auto and not a.lan:
        log("Modo automatico: enchufa el movil y listo.")
        threading.Thread(target=watcher, args=(a.adb, a.port, a.launch, a.wake),
                         daemon=True).start()
    log("=" * 46)

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle, args=(conn, addr, a.verbose), daemon=True).start()
    except KeyboardInterrupt:
        log("Adios.")
    finally:
        srv.close()


if __name__ == "__main__":
    main()
