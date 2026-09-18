#!/usr/bin/env python3
"""
USB Mouse - servidor PC (modo automatico).

Vigila el puerto USB: en cuanto detecta el movil por adb, monta el tunel
(`adb reverse`), despierta la pantalla y abre la app. La app se reconecta
sola. No hay que tocar nada.

Protocolo (lineas terminadas en \n; UTF-8, que es ASCII salvo en el texto de K):
    M<dx>,<dy>       mover cursor (relativo, float)
    S<dx>,<dy>       scroll en muescas (float)
    C<l|r|m>         click
    D<l|r|m>         boton pulsado
    U<l|r|m>         boton soltado
    K<texto>         escribir texto literal (sin saltos de linea)
    E<tecla>         tecla especial: pulsar y soltar
    E+<tecla>        tecla especial: mantener pulsada
    E-<tecla>        tecla especial: soltar
    H<mods>,<tecla>  atajo; mods son letras de c(ctrl) a(alt) s(shift) w(win)
    P                ping / keepalive

Nombres de tecla: enter back tab esc space up down left right home end
pgup pgdn del ins caps f1..f20, o un solo caracter literal ("Hc,c" = Ctrl+C).

Antes de nada va el emparejado. Sin el, abrir este puerto era escribir con el
teclado del PC: ejecucion de codigo. El codigo no viaja por la red, los dos
lados firman el numero aleatorio del otro y los dos se comprueban:

    PC    -> movil   U1 <nonceS>
    movil -> PC      A <hmac(codigo, "S:nonceS:nonceC")> <nonceC>
    PC    -> movil   B <hmac(codigo, "C:nonceC:nonceS")>
"""

import argparse
import hmac
import hashlib
import math
import os
import secrets
import socket
import stat
import subprocess
import sys
import threading
import time

try:
    from pynput.mouse import Button, Controller
    from pynput.keyboard import Controller as KeyController, Key
except ImportError:
    sys.exit("Falta pynput.  Instala con:  pip install pynput")

try:
    import tray
except ImportError:      # suelto o empaquetado sin el modulo: se sigue sin icono
    tray = None

BTN = {"l": Button.left, "r": Button.right, "m": Button.middle}

# Nombre corto del protocolo -> atributo de pynput.keyboard.Key. No se resuelven
# aqui: que teclas existen depende del sistema, asi que se miran con getattr.
SPECIAL = {
    "enter": "enter", "back": "backspace", "tab": "tab", "esc": "esc",
    "space": "space", "up": "up", "down": "down", "left": "left", "right": "right",
    "home": "home", "end": "end", "pgup": "page_up", "pgdn": "page_down",
    "del": "delete", "ins": "insert", "caps": "caps_lock",
}
SPECIAL.update({"f%d" % i: "f%d" % i for i in range(1, 21)})
MODS = {"c": "ctrl", "a": "alt", "s": "shift", "w": "cmd"}
PACKAGE = "com.eddie.usbmouse"
DISCOVERY_PORT = 8778
NO_WINDOW = 0x08000000 if os.name == "nt" else 0

LOG_LOCK = threading.Lock()
LOG_FILE = None

# ---------------------------------------------------------------- estado
# Lo que muestra el icono de bandeja. Se lleva aparte del log porque el log
# cuenta la historia y el icono solo dice como esta la cosa ahora mismo.

BANDEJA = None
ESTADO_LOCK = threading.Lock()
CONECTADOS = 0
ENCHUFADOS = 0


def pintar_estado():
    """Traduce el recuento a un texto y un color para la bandeja."""
    if BANDEJA is None:
        return
    with ESTADO_LOCK:
        conectados, enchufados = CONECTADOS, ENCHUFADOS
    if conectados:
        BANDEJA.estado_actual("Movil conectado", tray.VERDE)
    elif enchufados:
        BANDEJA.estado_actual("Movil enchufado, esperando la app", tray.AMBAR)
    else:
        BANDEJA.estado_actual("Esperando a que enchufes el movil", tray.GRIS)


def contar(conectados=0, enchufados=0):
    global CONECTADOS, ENCHUFADOS
    with ESTADO_LOCK:
        CONECTADOS = max(0, CONECTADOS + conectados)
        ENCHUFADOS = max(0, ENCHUFADOS + enchufados)
    pintar_estado()


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


class Keys:
    """Teclado del PC. Apunta lo que deja pulsado para soltarlo si se cuelga la linea."""

    def __init__(self):
        self.k = KeyController()
        self.held = set()

    def resolve(self, name):
        """Nombre del protocolo -> tecla de pynput. Un solo caracter es literal."""
        if len(name) == 1:
            return name
        attr = SPECIAL.get(name.lower())
        return getattr(Key, attr, None) if attr else None

    def type(self, text):
        if text:
            self.k.type(text)

    def tap(self, name):
        key = self.resolve(name)
        if key is None:
            return False
        self.k.press(key)
        self.k.release(key)
        return True

    def press(self, name):
        key = self.resolve(name)
        if key is None:
            return False
        self.k.press(key)
        self.held.add(key)
        return True

    def release(self, name):
        key = self.resolve(name)
        if key is None:
            return False
        self.k.release(key)
        self.held.discard(key)
        return True

    def combo(self, mods, name):
        key = self.resolve(name)
        if key is None:
            return False
        down = []
        for m in mods:
            attr = MODS.get(m)
            mk = getattr(Key, attr, None) if attr else None
            if mk is not None:
                down.append(mk)
        for mk in down:
            self.k.press(mk)
            self.held.add(mk)
        try:
            self.k.press(key)
            self.k.release(key)
        finally:
            for mk in reversed(down):
                try:
                    self.k.release(mk)
                except Exception:
                    pass
                self.held.discard(mk)
        return True

    def release_all(self):
        for key in list(self.held):
            try:
                self.k.release(key)
            except Exception:
                pass
        self.held.clear()


def handle(conn, addr, verbose=False):
    # Tope de conexiones: sin el, cualquiera podia abrir miles de sockets y
    # dejar un hilo colgado en cada uno.
    if not CONEXIONES.acquire(blocking=False):
        log("[!] Demasiadas conexiones a la vez; rechazo", addr[0])
        try:
            conn.close()
        except Exception:
            pass
        return
    cur = None
    keys = None
    contado = False
    conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    try:
        with conn.makefile("rb") as f:
            # Nada de lo que venga detras se toca hasta que el otro lado
            # demuestre que conoce el codigo.
            conn.settimeout(8)
            if not handshake(conn, f, addr):
                log("[-] Conexion rechazada", addr[0])
                return
            conn.settimeout(None)
            cur = Cursor()
            keys = Keys()
            log("[+] Movil conectado", addr[0])
            contar(conectados=+1)
            contado = True
            while True:
                # readline CON TOPE: sin el, un cliente que mande bytes sin
                # ningun fin de linea hace crecer el buffer hasta tumbar el
                # servidor por memoria.
                raw = f.readline(MAX_LINEA)
                if not raw:
                    break
                if not raw.endswith(b"\n") and len(raw) >= MAX_LINEA:
                    log("[!] Linea demasiado larga desde", addr[0], "- cierro")
                    break
                # solo el fin de linea: en K los espacios de los extremos son texto
                line = raw.rstrip(b"\r\n").decode("utf-8", "replace")
                if not line:
                    continue
                op, arg = line[0], line[1:]
                try:
                    if op == "M":
                        x, y = arg.split(",")
                        cur.move(numero(x), numero(y))
                    elif op == "S":
                        x, y = arg.split(",")
                        cur.scroll(numero(x), numero(y))
                    elif op == "C":
                        cur.click(arg)
                    elif op == "D":
                        cur.press(arg)
                    elif op == "U":
                        cur.release(arg)
                    elif op == "K":
                        keys.type(arg)
                    elif op == "E":
                        if arg[:1] == "+":
                            ok = keys.press(arg[1:])
                        elif arg[:1] == "-":
                            ok = keys.release(arg[1:])
                        else:
                            ok = keys.tap(arg)
                        if not ok and verbose:
                            log("    ? tecla desconocida en este sistema:", arg)
                    elif op == "H":
                        mods, _, name = arg.partition(",")
                        if not keys.combo(mods, name) and verbose:
                            log("    ? atajo no disponible:", arg)
                    elif op == "P":
                        pass
                    elif verbose:
                        log("    ? comando desconocido:", line)
                except Exception as e:
                    if verbose:
                        log("    ! error en", line, "->", e)
    except (ConnectionResetError, OSError, socket.timeout):
        pass
    finally:
        if cur is not None:
            cur.release_all()
        if keys is not None:
            keys.release_all()
        try:
            conn.close()
        except Exception:
            pass
        if contado:
            log("[-] Movil desconectado")
            contar(conectados=-1)
        CONEXIONES.release()


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


def carpeta():
    """
    Carpeta donde vive el programa.

    Con PyInstaller --onefile `__file__` apunta a la carpeta temporal en la que
    se descomprime el exe, que se borra al salir: el log acabaria ahi y el APK
    de al lado no se encontraria nunca. Congelado hay que mirar sys.executable.
    """
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


# ---------------------------------------------------------------- emparejado
#
# Por este socket viajan pulsaciones de teclado, o sea ejecucion de codigo con
# tu usuario. Sin codigo, cualquiera en la misma Wi-Fi abria el puerto con `nc`
# y escribia lo que quisiera; y por el tunel de adb podia hacerlo cualquier app
# del movil con solo permiso de INTERNET.
#
# El codigo NO viaja por la red: cada lado firma el numero aleatorio del otro.
# Y se comprueban LOS DOS, no solo el movil, porque quien conteste antes al
# sondeo UDP se llevaria todo lo tecleado, contrasenas incluidas.

# Sin I, L, O ni U: las que se confunden al copiar a mano.
ABC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
LARGO_CODIGO = 12          # 12 de 32 simbolos = 60 bits
CODE = ""

MAX_LINEA = 4096           # una linea de protocolo no llega ni a 64 bytes
MAX_CONEXIONES = 4
CONEXIONES = threading.Semaphore(MAX_CONEXIONES)

# Freno contra la fuerza bruta en linea: cinco fallos y esa IP se queda fuera
# un minuto. Con eso, 60 bits son inalcanzables aunque se pruebe sin parar.
FALLOS = {}
FALLOS_LOCK = threading.Lock()


def normal(raw):
    """Mayusculas, sin separadores y con las confusiones tipicas corregidas."""
    out = []
    for ch in (raw or "").upper():
        ch = {"I": "1", "L": "1", "O": "0", "U": "V"}.get(ch, ch)
        if ch in ABC:
            out.append(ch)
    return "".join(out)


def bonito(code):
    return "-".join(code[i:i + 4] for i in range(0, len(code), 4))


def firma(msg):
    return hmac.new(CODE.encode("utf-8"), msg.encode("utf-8"), hashlib.sha256).hexdigest()


def fichero_codigo():
    return os.path.join(carpeta(), "usbmouse-code.txt")


def cargar_codigo(forzado=None, nuevo=False):
    """Lee el codigo guardado, o genera uno la primera vez."""
    ruta = fichero_codigo()
    if forzado:
        c = normal(forzado)
        if len(c) != LARGO_CODIGO:
            log("[!] El codigo de --code debe tener %d simbolos de %s" % (LARGO_CODIGO, ABC))
            return None
        return c
    if not nuevo and os.path.exists(ruta):
        try:
            with open(ruta, "r", encoding="utf-8") as f:
                c = normal(f.read())
            if len(c) == LARGO_CODIGO:
                return c
        except Exception:
            pass
    c = "".join(secrets.choice(ABC) for _ in range(LARGO_CODIGO))
    try:
        with open(ruta, "w", encoding="utf-8") as f:
            f.write(bonito(c) + "\n")
        # Solo el dueno: en un PC compartido el codigo es la llave de todo.
        try:
            os.chmod(ruta, stat.S_IRUSR | stat.S_IWUSR)
        except Exception:
            pass
    except Exception as e:
        log("[!] No pude guardar el codigo:", e, "(se pedira otro al reiniciar)")
    return c


def bloqueada(ip):
    with FALLOS_LOCK:
        n, hasta = FALLOS.get(ip, (0, 0.0))
        return time.time() < hasta


def anotar_fallo(ip):
    with FALLOS_LOCK:
        n, hasta = FALLOS.get(ip, (0, 0.0))
        n += 1
        if n >= 5:
            hasta = time.time() + 60
            n = 0
            log("[!] Demasiados codigos malos desde", ip, "- bloqueada 60 s")
        FALLOS[ip] = (n, hasta)


def limpiar_fallos(ip):
    with FALLOS_LOCK:
        FALLOS.pop(ip, None)


def leer_linea(f, maximo=512):
    raw = f.readline(maximo)
    if not raw:
        return None
    if not raw.endswith(b"\n") and len(raw) >= maximo:
        return None
    return raw.decode("utf-8", "replace").strip()


def handshake(conn, f, addr):
    """
    Apreton mutuo. True solo si los dos lados prueban que conocen el codigo.

        PC    -> movil   U1 <nonceS>
        movil -> PC      A <hmac(S:nonceS:nonceC)> <nonceC>
        PC    -> movil   B <hmac(C:nonceC:nonceS)>
    """
    ip = addr[0]
    if bloqueada(ip):
        return False
    nonce_s = secrets.token_hex(16)
    conn.sendall(("U1 %s\n" % nonce_s).encode("ascii"))
    linea = leer_linea(f)
    if not linea:
        return False
    p = linea.split(" ")
    if len(p) != 3 or p[0] != "A" or len(p[2]) != 32:
        anotar_fallo(ip)
        return False
    nonce_c = p[2]
    if not hmac.compare_digest(p[1], firma("S:%s:%s" % (nonce_s, nonce_c))):
        # medio segundo de castigo: convierte cualquier barrido en inviable
        time.sleep(0.5)
        anotar_fallo(ip)
        log("[!] Codigo incorrecto desde", ip)
        return False
    conn.sendall(("B %s\n" % firma("C:%s:%s" % (nonce_c, nonce_s))).encode("ascii"))
    limpiar_fallos(ip)
    return True


def numero(txt):
    """
    Float del protocolo, saneado. `inf` y `nan` envenenaban el acumulador del
    cursor: una sola linea "Minf,0" y el raton no se movia hasta reconectar.
    """
    v = float(txt)
    if not math.isfinite(v):
        raise ValueError("no finito")
    return max(-10000.0, min(10000.0, v))


def apk_a_mano():
    """APK para instalar: primero el que este junto al servidor, si no el de gradle."""
    base = carpeta()
    for rel in (
        ("UsbMouse.apk",),
        ("..", "android", "app", "build", "outputs", "apk", "debug", "app-debug.apk"),
        ("..", "android", "app", "build", "outputs", "apk", "release", "app-release.apk"),
    ):
        ruta = os.path.normpath(os.path.join(base, *rel))
        if os.path.isfile(ruta):
            return ruta
    return None


def esta_instalada(exe, serial):
    """True/False, o None si no se ha podido averiguar."""
    r = adb(exe, "-s", serial, "shell", "pm", "list", "packages", PACKAGE)
    if r is None or r.returncode != 0:
        return None
    # comparacion por linea entera: "pm list packages" filtra por prefijo, asi
    # que un paquete que empiece igual daria un falso positivo
    for linea in (r.stdout or "").splitlines():
        if linea.strip() == "package:" + PACKAGE:
            return True
    return False


def instalar(exe, serial, apk):
    log("    instalando la app:", os.path.basename(apk))
    r = adb(exe, "-s", serial, "install", "-r", apk, timeout=240)
    salida = ((r.stdout or "") + (r.stderr or "")) if r else ""
    if r is not None and r.returncode == 0 and "Success" in salida:
        log("    app instalada")
        return True
    log("    ! no pude instalar:", salida.strip().splitlines()[-1] if salida.strip() else "adb no responde")
    return False


def prepare(exe, serial, port, launch, wake, install=True):
    """Monta el tunel y, si toca, instala y abre la app en ese movil."""
    r = adb(exe, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}")
    if r is None or r.returncode != 0:
        log("    ! adb reverse fallo:", (r.stderr or r.stdout).strip() if r else "adb no responde")
        return False
    log("    tunel USB listo (tcp:%d)" % port)
    if wake:
        adb(exe, "-s", serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    if install and esta_instalada(exe, serial) is False:
        apk = apk_a_mano()
        if apk:
            instalar(exe, serial, apk)
        else:
            log("    (la app no esta y no hay APK: compila con 'gradlew assembleDebug'")
            log("     o deja un UsbMouse.apk junto a este servidor)")
    if launch:
        # El codigo se le pasa al movil por el propio cable, asi que por USB no
        # hay que teclear nada. El canal ya esta autorizado: para llegar aqui el
        # usuario ha tenido que aceptar la depuracion USB de este PC.
        r = adb(exe, "-s", serial, "shell", "am", "start", "-n", f"{PACKAGE}/.MainActivity",
                "-e", "code", CODE)
        if r is not None and r.returncode == 0 and "Error" not in (r.stderr or ""):
            log("    app abierta en el movil")
        else:
            log("    (la app no esta instalada todavia en ese movil)")
    return True


def watcher(exe, port, launch, wake, install=True, interval=2.0):
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
            if prepare(exe, s, port, launch, wake, install):
                ready.add(s)
                contar(enchufados=+1)
        for s in ready - online:
            log("[-] Movil desenchufado:", s)
            contar(enchufados=-1)
        ready &= online
        time.sleep(interval)


def discovery_responder(port, disc_port=DISCOVERY_PORT):
    """
    Responde a los sondeos del movil para que encuentre la IP del PC solo.

    Solo se levanta con --lan: sin el, el movil llega por el tunel de adb a su
    propio 127.0.0.1 y no hace falta buscar a nadie, asi que antes se estaba
    escuchando en 0.0.0.0 y contestando a cualquiera de la red para nada.

    El sondeo tiene que venir firmado y la respuesta va firmada:

      - firmar la respuesta impide que un impostor que conteste antes se lleve
        la conexion, y con ella todo lo que se teclee;
      - exigir firma en el sondeo impide que esto sea un oraculo. Si contestara
        con una firma a cualquiera que preguntase, bastaria pedirsela una vez y
        romper el codigo despues, sin prisa y sin volver a tocar la red.
    """
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
            data, addr = s.recvfrom(160)
            texto = data.decode("ascii", "replace").strip()
            if not texto.startswith("USBMOUSE?"):
                continue
            p = texto.split(" ")
            # "USBMOUSE? <nonce> <firma>"
            if len(p) != 3 or len(p[1]) != 32:
                continue
            if not hmac.compare_digest(p[2], firma("D:%s" % p[1])):
                # Ni un error ni un log por sondeo: quien no sepa el codigo no
                # se entera siquiera de que hay algo aqui.
                continue
            resp = "USBMOUSE:%d %s" % (port, firma("R:%s:%d" % (p[1], port)))
            s.sendto(resp.encode("ascii"), addr)
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

def abrir_log():
    """Doble clic en la bandeja: enseña el registro con el programa de siempre."""
    if not LOG_FILE or not os.path.isfile(LOG_FILE):
        return
    try:
        os.startfile(LOG_FILE)      # solo existe en Windows, que es donde hay bandeja
    except Exception as e:
        log("[!] No pude abrir el registro:", e)


def salir():
    log("Adios (desde la bandeja).")
    if BANDEJA:
        BANDEJA.parar()
    # los hilos son demonios y no hay nada que guardar: el log se escribe linea
    # a linea segun pasa, asi que cortar por lo sano es seguro aqui
    os._exit(0)


def main():
    global LOG_FILE, BANDEJA, CODE
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
    p.add_argument("--no-install", dest="install", action="store_false", default=True,
                   help="no instalar el APK aunque el movil no lo tenga")
    p.add_argument("--no-tray", dest="tray", action="store_false", default=True,
                   help="no sacar el icono de bandeja (solo Windows)")
    p.add_argument("--lan", action="store_true",
                   help="escuchar en todas las interfaces (anclaje USB o Wi-Fi, sin adb)")
    p.add_argument("--log", nargs="?", const="usbmouse.log", default=None,
                   help="volcar la salida a un fichero (modo silencioso)")
    p.add_argument("--code", default=None,
                   help="codigo de emparejamiento a usar (por defecto, el guardado)")
    p.add_argument("--new-code", action="store_true",
                   help="generar un codigo nuevo y olvidar el anterior")
    p.add_argument("-v", "--verbose", action="store_true")
    a = p.parse_args()

    if a.log:
        LOG_FILE = os.path.join(carpeta(), a.log)

    CODE = cargar_codigo(a.code, a.new_code)
    if not CODE:
        return

    host = "0.0.0.0" if a.lan else "127.0.0.1"
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        srv.bind((host, a.port))
    except OSError as e:
        log("[!] No puedo abrir el puerto", a.port, "->", e, "(ya hay otro servidor corriendo?)")
        return
    srv.listen(MAX_CONEXIONES * 2)

    log("=" * 46)
    log("USB Mouse - escuchando en %s:%d" % (host, a.port))
    log("")
    log("   CODIGO DE EMPAREJADO:  %s" % bonito(CODE))
    log("   (guardado en %s)" % os.path.basename(fichero_codigo()))
    log("   Por el cable de USB se le pasa solo al movil; por Wi-Fi hay que")
    log("   escribirlo una vez en los ajustes de la app. No lo compartas: con")
    log("   el, quien lo tenga puede teclear en este PC.")
    log("")
    if a.lan:
        log("IPs de este PC:", ", ".join(local_ips()) or "(desconocidas)")
        log("En anclaje USB suele ser 192.168.42.x")
    if a.tray and tray is not None:
        BANDEJA = tray.start("USB Mouse", al_salir=salir, al_abrir_log=abrir_log)
        if BANDEJA:
            log("Icono de bandeja activo (doble clic = registro, derecho = menu)")
        pintar_estado()
    # Solo con --lan: por el tunel de adb el movil habla con su propio
    # 127.0.0.1 y no tiene a quien buscar, asi que abrir el UDP no aportaba
    # nada y delataba el equipo a toda la red.
    if a.lan:
        threading.Thread(target=discovery_responder, args=(a.port,), daemon=True).start()
    if a.auto and not a.lan:
        log("Modo automatico: enchufa el movil y listo.")
        threading.Thread(target=watcher,
                         args=(a.adb, a.port, a.launch, a.wake, a.install),
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
