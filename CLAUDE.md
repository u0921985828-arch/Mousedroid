# USB Mouse

El móvil hace de ratón del PC por cable. App Android (Kotlin) → socket TCP → `adb reverse` o
anclaje USB → servidor Python que mueve el cursor real con `pynput`. Sin root, sin Bluetooth,
sin internet en tiempo de ejecución.

## Stack exacto

| | |
|---|---|
| AGP / Kotlin | 8.5.2 / 1.9.24 |
| minSdk / compileSdk / target | 24 / 34 / 34 |
| jvmTarget | 17 |
| Dependencias | `androidx.core:core-ktx:1.13.1` y nada más |
| Servidor | Python 3 + `pynput`, empaquetable con PyInstaller |

**Sin XML de layout y sin recursos propios.** Toda la interfaz se construye en código y los
colores salen de `Monet`. No añadas `res/layout`, `res/values/colors.xml` ni Compose: la
ausencia de dependencias es deliberada, el APK pesa poco y compila sin sorpresas.

## Mapa de ficheros

```
android/app/src/main/java/com/eddie/usbmouse/
  MainActivity.kt   chasis, cabecera, panel de ajustes, bucle de conexión. Implementa DeckIO.
  Deck.kt           fábrica de disposiciones (6 modos) + GlyphView, PipsView, PadStack, plate()
  TouchpadView.kt   superficie de gestos (RUTA CALIENTE) + HaloView + ScrollStripView
  MouseClient.kt    socket, coalescencia de movimiento, emisión sin asignaciones
  Discovery.kt      sondeo UDP para encontrar el PC sin escribir IP
  Monet.kt          paleta dinámica (system_* en API 31+, muestreo del fondo por debajo)
  Textures.kt       ruido procedural cacheado + MetalDrawable
  PowerReceiver.kt  abre la app al enchufar (ACTION_POWER_CONNECTED)
server/
  server.py         vigilante de USB + servidor de comandos
  build-exe.bat     genera UsbMouse.exe portátil
  install-autostart.bat / uninstall-autostart.bat
```

Documentos de diseño en la raíz: `usb-mouse-proporciones.html` (cotas), `usb-mouse-modos.html`
(prototipo interactivo), `usb-mouse-guia.html` (guía de uso).

## Protocolo

Líneas ASCII terminadas en `\n`, del móvil al PC:

```
M<dx>,<dy>   mover, relativo, float
S<dx>,<dy>   scroll en muescas
C<l|r|m>     click
D<l|r|m>     botón pulsado
U<l|r|m>     botón soltado
P            keepalive
```

Puerto por defecto 8777. Descubrimiento en UDP 8778: el móvil lanza `USBMOUSE?` a broadcast y
el PC responde `USBMOUSE:<puerto>`.

## Reglas de la ruta caliente

Se rompió una vez por esto; no lo deshagas.

- **Nada de `Color.parseColor` dentro de `onDraw`.** Los colores se resuelven a `Int` en
  construcción o en `applyPalette()`.
- **El movimiento del dedo no invalida el cristal.** El halo es `HaloView`, vista aparte con
  capa de hardware que se desplaza con `translationX/Y`. Si vuelves a dibujar el halo dentro de
  `TouchpadView`, vuelve el jank.
- **Cero asignaciones por evento de movimiento.** `MouseClient.emit()` escribe los dígitos a
  mano en un `ByteArray` reutilizado. No metas `String.format` ahí.
- **`MainActivity.move()` no toca la interfaz.** El lector de comandos solo refleja acciones
  discretas (clics, macros, cambios de escalón).
- Los shaders (`LinearGradient`, `RadialGradient`, `BitmapShader`) se crean en `onSizeChanged`
  o en `init`, nunca por fotograma.
- `Textures` genera dos bitmaps una sola vez y los repite; no hay bucles de píxeles en dibujado.

## Formato del protocolo

`MouseClient` emite con separador decimal `.` siempre. Si alguna vez vuelves a usar
`String.format`, tiene que llevar `Locale.US`: en configuración española la coma decimal rompe
el parseo del servidor.

## Paleta y cotas fijas

Los tonos de `Monet` en la rama sin `system_*` (Android 7–11), en HSL sobre el tono semilla:

```
deckHi  5% 31     plateHi 7% 17     glassHi 9% 11.5    etch     8% 68
deck    5% 28     plateLo 7% 14     glassLo 9%  8.5    etchDim  7% 48
deckLo  5% 24                                          accent  55% 72
                                                       accentSoft 45% 58
```

Proporciones (todo en dp, base 4):

```
margen exterior 11    cabecera 30       hueco entre piezas 7
cristal 1,62:1        radio 14          banda de clic 32% del alto
zonas de clic 3:2:3   raíl 36           filos 1 px
ratón: cuerpo 1:1,85, botones 44%, ranura 2, rueda 40×80, rieles 34
LED 7   puntos 7 paso 11   glifos 11   retícula 22
objetivo táctil mínimo 48
```

Orden de sacrificio cuando la pantalla no da: deck libre → escalones → relación 1,62 → banda de
clic. **La superficie de trabajo no se toca nunca.** Umbral de pantalla corta: 620 dp de alto
(`Deck.short()`).

## Piezas frágiles

- **`PadStack.onSizeChanged`** fija el alto de la banda de clic al 32%. La banda se añade
  después del pad en el `FrameLayout`, así que intercepta el toque antes que el motor de
  gestos: si reordenas los hijos, el clic deja de funcionar.
- **`TouchpadView.onMeasure`** impone la relación 1,62 solo cuando `aspect > 0`. `Deck` lo pone
  a 0 en pantallas cortas.
- **Modo ratón con nivel ≥ 1 apaga `tapToClick` y `twoFingerScroll`** a propósito: con botones
  físicos, un toque en el sensor es un fallo, no una comodidad.
- **Gaming apaga la aceleración** (`accel = tier != 2`). No lo "arregles".
- **`adb reverse` va del móvil al PC**: el móvil escucha en su `127.0.0.1:8777` y el tráfico
  sale por el cable. El servidor escucha en el `127.0.0.1` del PC. No inviertas la dirección.
- **`PowerReceiver`** solo puede abrir la Activity si está concedido "Mostrar sobre otras apps".
  Sin ese permiso falla en silencio, y es correcto que falle en silencio.
- Cambiar de modo reconstruye `stageHost`, no la Activity: la conexión no debe cortarse.

## Comandos

```bash
# Android
cd android && gradlew installDebug

# Servidor a mano, con traza de cada comando
cd server && start.bat -v

# Servidor portátil
cd server && build-exe.bat && install-autostart.bat
```

## Pendiente

- Instalar el APK solo desde el vigilante (`adb shell pm list packages` + `adb install -r`).
- Teclado: añadir `K<texto>` al protocolo y `pynput.keyboard` al servidor.
- Icono de bandeja en el PC para ver el estado sin abrir el log.
