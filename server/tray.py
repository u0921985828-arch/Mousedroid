#!/usr/bin/env python3
"""
Icono de bandeja para ver el estado sin abrir el log.

Windows a pelo con ctypes: sin dependencias nuevas, asi que el .exe de
PyInstaller no engorda. En cualquier otro sistema `start()` devuelve None y el
servidor sigue igual. Si algo falla montandolo tampoco pasa nada: el icono es
comodidad, no funcion, y por eso todo esto va envuelto en try/except.

El icono se dibuja aqui mismo (un disco de color) en vez de cargar un .ico,
para no tener que arrastrar un fichero suelto junto al ejecutable.
"""

import os
import threading

# Colores de estado, en BGR porque es el orden que quieren los mapas de bits
# de Windows. Son los mismos tres del LED de la app.
GRIS = (0x60, 0x5B, 0x57)
AMBAR = (0x5A, 0xB4, 0xD9)
VERDE = (0x9A, 0xDC, 0x8F)

_ID_LOG = 1001
_ID_SALIR = 1002
_WM_TRAY = 0x0400 + 20          # WM_APP + 20


def _disco(bgr):
    """Icono de 16x16: un disco del color pedido, el resto transparente."""
    lado = 16
    centro = (lado - 1) / 2.0
    radio = lado / 2.0 - 1.0
    color = bytearray()
    mascara = bytearray()
    for y in range(lado):
        bits = 0
        for x in range(lado):
            dentro = ((x - centro) ** 2 + (y - centro) ** 2) <= radio ** 2
            if dentro:
                color += bytes((bgr[0], bgr[1], bgr[2], 0xFF))
            else:
                color += b"\x00\x00\x00\x00"
                # 1 en la mascara AND = ese pixel deja pasar el fondo
                bits |= 1 << (15 - x)
        mascara += bytes(((bits >> 8) & 0xFF, bits & 0xFF))
    return bytes(mascara), bytes(color)


class Bandeja:
    """Ventana oculta + icono. Todo el bucle de mensajes vive en su propio hilo."""

    def __init__(self, titulo, al_salir=None, al_abrir_log=None):
        import ctypes
        from ctypes import wintypes

        self.ct = ctypes
        self.wt = wintypes
        self.titulo = titulo
        self.al_salir = al_salir
        self.al_abrir_log = al_abrir_log
        self.hwnd = None
        self.iconos = {}
        self.estado = ("Iniciando...", GRIS)
        self.listo = threading.Event()

        self.u32 = ctypes.WinDLL("user32", use_last_error=True)
        self.shell = ctypes.WinDLL("shell32", use_last_error=True)
        self.k32 = ctypes.WinDLL("kernel32", use_last_error=True)

        LRESULT = ctypes.c_ssize_t
        self.WNDPROC = ctypes.WINFUNCTYPE(
            LRESULT, wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM
        )

        # Sin argtypes/restype ctypes asume int de 32 bits, y en 64 bits los
        # manejadores se parten por la mitad. Es el fallo clasico de esta API,
        # y da un error que no se parece en nada a su causa.
        c_int = ctypes.c_int
        self.u32.DefWindowProcW.restype = LRESULT
        self.u32.DefWindowProcW.argtypes = [
            wintypes.HWND, wintypes.UINT, wintypes.WPARAM, wintypes.LPARAM
        ]
        self.k32.GetModuleHandleW.restype = wintypes.HMODULE
        self.k32.GetModuleHandleW.argtypes = [wintypes.LPCWSTR]
        self.u32.CreateWindowExW.restype = wintypes.HWND
        self.u32.CreateWindowExW.argtypes = [
            wintypes.DWORD, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.DWORD,
            c_int, c_int, c_int, c_int,
            wintypes.HWND, wintypes.HMENU, wintypes.HINSTANCE, wintypes.LPVOID,
        ]
        self.u32.CreateIcon.restype = wintypes.HICON
        self.u32.CreateIcon.argtypes = [
            wintypes.HINSTANCE, c_int, c_int, ctypes.c_ubyte, ctypes.c_ubyte,
            ctypes.c_char_p, ctypes.c_char_p,
        ]
        self.u32.CreatePopupMenu.restype = wintypes.HMENU
        self.u32.AppendMenuW.argtypes = [
            wintypes.HMENU, wintypes.UINT, ctypes.c_size_t, wintypes.LPCWSTR
        ]
        self.u32.TrackPopupMenu.restype = c_int
        self.u32.TrackPopupMenu.argtypes = [
            wintypes.HMENU, wintypes.UINT, c_int, c_int, c_int,
            wintypes.HWND, wintypes.LPVOID,
        ]
        self.u32.DestroyMenu.argtypes = [wintypes.HMENU]
        self.u32.SetForegroundWindow.argtypes = [wintypes.HWND]
        self.u32.GetMessageW.argtypes = [
            wintypes.LPVOID, wintypes.HWND, wintypes.UINT, wintypes.UINT
        ]
        self.shell.Shell_NotifyIconW.argtypes = [wintypes.DWORD, wintypes.LPVOID]

        class WNDCLASS(ctypes.Structure):
            _fields_ = [
                ("style", wintypes.UINT),
                ("lpfnWndProc", self.WNDPROC),
                ("cbClsExtra", ctypes.c_int),
                ("cbWndExtra", ctypes.c_int),
                ("hInstance", wintypes.HINSTANCE),
                ("hIcon", wintypes.HICON),
                ("hCursor", wintypes.HANDLE),
                ("hbrBackground", wintypes.HANDLE),
                ("lpszMenuName", wintypes.LPCWSTR),
                ("lpszClassName", wintypes.LPCWSTR),
            ]

        class NOTIFYICONDATA(ctypes.Structure):
            _fields_ = [
                ("cbSize", wintypes.DWORD),
                ("hWnd", wintypes.HWND),
                ("uID", wintypes.UINT),
                ("uFlags", wintypes.UINT),
                ("uCallbackMessage", wintypes.UINT),
                ("hIcon", wintypes.HICON),
                ("szTip", wintypes.WCHAR * 128),
                ("dwState", wintypes.DWORD),
                ("dwStateMask", wintypes.DWORD),
                ("szInfo", wintypes.WCHAR * 256),
                ("uVersion", wintypes.UINT),
                ("szInfoTitle", wintypes.WCHAR * 64),
                ("dwInfoFlags", wintypes.DWORD),
            ]

        self.WNDCLASS = WNDCLASS
        self.NOTIFYICONDATA = NOTIFYICONDATA
        # el objeto del callback tiene que sobrevivir a la llamada: si lo recoge
        # el recolector, Windows salta a memoria liberada
        self.proc = self.WNDPROC(self._wndproc)

    # ------------------------------------------------------------ interno

    def _icono(self, bgr):
        if bgr not in self.iconos:
            mascara, color = _disco(bgr)
            self.iconos[bgr] = self.u32.CreateIcon(
                None, 16, 16, 1, 32, mascara, color
            )
        return self.iconos[bgr]

    def _datos(self, texto, bgr):
        nid = self.NOTIFYICONDATA()
        nid.cbSize = self.ct.sizeof(self.NOTIFYICONDATA)
        nid.hWnd = self.hwnd
        nid.uID = 1
        nid.uFlags = 0x01 | 0x02 | 0x04        # MESSAGE | ICON | TIP
        nid.uCallbackMessage = _WM_TRAY
        nid.hIcon = self._icono(bgr)
        nid.szTip = ("%s\n%s" % (self.titulo, texto))[:127]
        return nid

    def _menu(self):
        menu = self.u32.CreatePopupMenu()
        self.u32.AppendMenuW(menu, 0, _ID_LOG, "Abrir el registro")
        self.u32.AppendMenuW(menu, 0x800, 0, None)      # MF_SEPARATOR
        self.u32.AppendMenuW(menu, 0, _ID_SALIR, "Salir")
        pt = self.wt.POINT()
        self.u32.GetCursorPos(self.ct.byref(pt))
        # sin esto el menu no se cierra al pinchar fuera: es un requisito viejo
        # y documentado de TrackPopupMenu
        self.u32.SetForegroundWindow(self.hwnd)
        elegido = self.u32.TrackPopupMenu(
            menu, 0x0100, pt.x, pt.y, 0, self.hwnd, None   # TPM_RETURNCMD
        )
        self.u32.DestroyMenu(menu)
        if elegido == _ID_SALIR and self.al_salir:
            self.al_salir()
        elif elegido == _ID_LOG and self.al_abrir_log:
            self.al_abrir_log()

    def _wndproc(self, hwnd, msg, wparam, lparam):
        try:
            if msg == _WM_TRAY:
                bajo = lparam & 0xFFFF
                if bajo in (0x0205, 0x007B):           # WM_RBUTTONUP, WM_CONTEXTMENU
                    self._menu()
                elif bajo == 0x0203 and self.al_abrir_log:   # doble clic izquierdo
                    self.al_abrir_log()
                return 0
            if msg == 0x0002:                          # WM_DESTROY
                self.u32.PostQuitMessage(0)
                return 0
        except Exception:
            pass
        return self.u32.DefWindowProcW(hwnd, msg, wparam, lparam)

    def _bucle(self):
        from ctypes import wintypes
        ct = self.ct
        try:
            inst = self.k32.GetModuleHandleW(None)
            clase = self.WNDCLASS()
            clase.lpfnWndProc = self.proc
            clase.hInstance = inst
            clase.lpszClassName = "UsbMouseBandeja"
            if not self.u32.RegisterClassW(ct.byref(clase)):
                # ya registrada de una ejecucion anterior en el mismo proceso
                pass
            self.hwnd = self.u32.CreateWindowExW(
                0, "UsbMouseBandeja", self.titulo, 0,
                0, 0, 0, 0, None, None, inst, None,
            )
            if not self.hwnd:
                self.listo.set()
                return
            texto, bgr = self.estado
            self.shell.Shell_NotifyIconW(0, ct.byref(self._datos(texto, bgr)))  # NIM_ADD
            self.listo.set()

            msg = wintypes.MSG()
            while self.u32.GetMessageW(ct.byref(msg), None, 0, 0) > 0:
                self.u32.TranslateMessage(ct.byref(msg))
                self.u32.DispatchMessageW(ct.byref(msg))
        except Exception:
            self.listo.set()
        finally:
            self._quitar()

    def _quitar(self):
        try:
            if self.hwnd:
                nid = self.NOTIFYICONDATA()
                nid.cbSize = self.ct.sizeof(self.NOTIFYICONDATA)
                nid.hWnd = self.hwnd
                nid.uID = 1
                self.shell.Shell_NotifyIconW(2, self.ct.byref(nid))   # NIM_DELETE
                self.hwnd = None
        except Exception:
            pass

    # ------------------------------------------------------------ publico

    def arrancar(self):
        threading.Thread(target=self._bucle, daemon=True).start()
        self.listo.wait(timeout=5)
        return self.hwnd is not None

    def estado_actual(self, texto, bgr):
        self.estado = (texto, bgr)
        try:
            if self.hwnd:
                self.shell.Shell_NotifyIconW(
                    1, self.ct.byref(self._datos(texto, bgr))     # NIM_MODIFY
                )
        except Exception:
            pass

    def parar(self):
        self._quitar()


def start(titulo, al_salir=None, al_abrir_log=None):
    """Devuelve la bandeja, o None si este sistema no la tiene o fallo al crearla."""
    if os.name != "nt":
        return None
    try:
        b = Bandeja(titulo, al_salir, al_abrir_log)
        return b if b.arrancar() else None
    except Exception:
        return None
