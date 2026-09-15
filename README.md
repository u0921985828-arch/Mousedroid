# USB Mouse — el móvil como ratón del PC por cable

Dos piezas: una app Android (Kotlin, sin dependencias raras) y un servidor Python en el PC.
El tráfico va por el cable USB mediante `adb reverse`, así que no pasa por la red ni por Wi-Fi.

```
[ App Android ] --socket--> localhost:8777 (móvil)
        |  adb reverse (cable USB)
        v
[ server.py ] --> pynput --> cursor real del PC
```

---

## Instalación (una sola vez)

Necesitas Python 3 y **adb** (Android Platform-Tools) en el PATH.

**PC** (dos pasos, una vez en la vida):
```
server\build-exe.bat          genera UsbMouse.exe portátil (necesita Python solo aquí)
server\install-autostart.bat  lo deja arrancando con Windows, sin ventana
```
A partir de ahí el PC no necesita Python ni nada instalado: es un único `.exe` de ~10 MB
que arranca solo y se queda en segundo plano. Log en `usbmouse.log`.
Para quitarlo: `uninstall-autostart.bat`. Si prefieres no compilar, `install-autostart.bat`
cae de vuelta a Python automáticamente.

**Móvil:**
1. Abre `android/` en Android Studio → Run (o `cd android && ./gradlew installDebug`,
   `gradlew.bat` en Windows: el wrapper se baja Gradle 8.7 solo la primera vez).
   Sin SDK a mano: **Actions ▸ Build APK ▸ Run workflow** deja el `UsbMouse.apk` como
   artefacto del run, listo para descargar e instalar en el móvil.
2. Vía ADB: activa **Depuración USB** y marca "Permitir siempre desde este equipo".
   Vía anclaje: Ajustes → Conexión compartida → **Anclaje USB**, y marca "Abrir al enchufar" en la app.

El campo de host viene en `auto`: la app prueba primero el túnel adb (`127.0.0.1`) y,
si no hay, lanza un sondeo UDP para encontrar el PC sola. No hay que escribir ninguna IP.

## Uso diario

Enchufar el cable. Nada más.

El servidor vigila el USB cada 2 s; al detectar el móvil monta `adb reverse`,
enciende la pantalla (`KEYCODE_WAKEUP`) y abre la app con `am start`.
La app arranca con "Conectar sola" activo y reintenta cada 1,5 s hasta enlazar,
así que da igual quién arranque primero. Al desenchufar y volver a enchufar,
todo se rehace solo.

Para pruebas manuales con ventana y logs: `server\start.bat -v`.

Si el móvil no tiene la app instalada, el servidor la instala él: busca `UsbMouse.apk` a su
lado y, si no está, el APK que deja `./gradlew assembleDebug`.

En Windows aparece además un **icono en la bandeja**: gris esperando, ámbar con el móvil
enchufado y verde con la app conectada. Doble clic abre el registro, botón derecho da el menú.
No necesita nada instalado, va con `ctypes`; en otros sistemas simplemente no sale.

Opciones: `--port 9000`, `--adb C:\platform-tools\adb.exe`, `--no-launch`,
`--no-wake`, `--no-auto`, `--no-install`, `--no-tray`, `--lan`, `--log`, `-v`.

---

## Aspecto

El teléfono se presenta como un aparato, no como una app: chasis de aluminio anodizado
(degradado vertical con filo claro arriba y sombra abajo), cristal hundido con bisel interior
para el touchpad, franja de clic de una sola pieza con dos grietas de 1 px, serigrafía tenue
en minúscula y un LED de 7 px como único indicador — verde conectado, ámbar buscando, gris parado.
Modo inmersivo: las barras del sistema se ocultan.

A la derecha del LED corre un lector monoespaciado con el último comando enviado
(`M 12.4,-3.1`, `C l`), útil para depurar sin abrir logcat.

`usb-mouse-modos.html` es un prototipo funcional de esa interfaz en el navegador, con un
escritorio simulado para probar la sensación sin compilar nada. `usb-mouse-proporciones.html`
tiene las cotas y `usb-mouse-guia.html` la guía de uso.

## Gestos

| Gesto | Acción |
|---|---|
| 1 dedo arrastrando | mover cursor |
| toque corto | click izquierdo |
| toque con 2 dedos | click derecho |
| toque con 3 dedos | click central |
| 2 dedos arrastrando | scroll vertical y horizontal |
| mantener pulsado y mover | arrastrar (botón izq. retenido) |
| doble toque + mover | arrastrar |
| franja lateral derecha | scroll con un dedo |

En la cabecera hay un glifo de teclado: abre el teclado del sistema y lo que escribas va al PC,
tildes y eñes incluidas. Debajo quedan las teclas que un móvil no da — `esc`, tabulador,
flechas, retroceso e intro (las que se repiten si las mantienes) — y copiar / pegar / deshacer.
En modo ratón, los botones laterales mandan Alt+← y Alt+→ (atrás y adelante), y en gaming
G1 y G2 mandan F13 y F14, que son teclas que ningún programa usa por su cuenta.

Abajo: botones izquierdo / medio / derecho, sensibilidad (0.5–6.5), aceleración,
scroll natural, **conectar sola** y **abrir al enchufar**. Todo se guarda en `SharedPreferences`.

---

## Modo B: sin depuración USB (anclaje)

Si no quieres activar la depuración:

1. Móvil → Ajustes → Conexión compartida → **Anclaje USB**.
2. En el PC: `python server.py --lan` (imprime las IPs; en anclaje suele ser `192.168.42.x`).
3. En la app pon esa IP en lugar de `127.0.0.1`.

Sigue yendo todo por el cable, pero por la interfaz de red USB en vez de por adb.

Aquí el PC no puede abrir la app (no hay adb), así que activa **Abrir al enchufar**
en la app: usa `ACTION_POWER_CONNECTED` y necesita el permiso *Mostrar sobre otras apps*
(la app te lleva a la pantalla de Ajustes al marcar la casilla). Filtra por carga USB,
así que un cargador de pared no la abre.

---

## Detalles técnicos

- **Protocolo**: líneas UTF-8, `M dx,dy` / `S dx,dy` / `C b` / `D b` / `U b` / `K texto` / `E tecla` / `H mods,tecla` / `P`. Trivial de extender.
- **Latencia**: `TCP_NODELAY` activo; los movimientos se acumulan y se envían a ~120 Hz (8 ms) para no saturar el socket. Los clicks salen por una cola aparte, sin esperar.
- **Restos fraccionarios**: tanto la app como el servidor acumulan decimales, así que los movimientos finos no se pierden.
- **Descubrimiento**: la app manda `USBMOUSE?` por UDP 8778 a broadcast y el PC responde con su IP y puerto. Cero configuración.
- **Seguridad**: si se corta la conexión con un botón o una tecla pulsados, el servidor los suelta (`release_all`).
- **Teclado**: la app no dibuja letras; usa el teclado del sistema y envuelve la `InputConnection` para capturar lo que escribes, que es lo único que funciona igual en todos los teclados.
- El servidor escucha solo en `127.0.0.1` salvo que uses `--lan`.

### Por qué hace falta algo en el PC

Para que Windows lo viera como un ratón con sus propios drivers, el móvil tendría que
anunciarse como dispositivo **USB HID**. Android soporta modo gadget, pero solo expone
MTP, PTP, MIDI, anclaje y ADB: la función `f_hid` del kernel no está accesible sin **root**
(y sin que el kernel la traiga compilada). No es una limitación de esta app.

Las únicas alternativas sin software en el PC son Bluetooth HID (`BluetoothHidDevice`,
Android 9+, pero entonces no es por cable) o root. Por eso aquí el PC lleva un `.exe`
portátil de un solo archivo: es la huella más pequeña posible por USB.

### Lo que no se puede automatizar

- **La pantalla bloqueada.** `KEYCODE_WAKEUP` la enciende, pero el PIN/huella lo tienes que meter tú. Se podría mandar el PIN con `adb shell input text`, pero dejar tu PIN en un `.bat` es mala idea.
- **La autorización de depuración USB** la primera vez en cada PC. Marcando "permitir siempre" no vuelve a salir.

### Ideas para ampliar

- Multimedia: `pynput` puede mandar play/pause/volumen.
- Giroscopio como modo puntero (tipo mando Wii).
- Un opcode que acepte una secuencia entera de teclas, para macros largas.
