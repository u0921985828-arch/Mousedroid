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
android/
  gradlew, gradlew.bat, gradle/wrapper/   wrapper fijado a Gradle 8.7 (AGP 8.5.2 pide 8.7+)
  app/src/main/java/com/eddie/usbmouse/
    MainActivity.kt   chasis, cabecera, panel de ajustes, bucle de conexión. Implementa DeckIO.
    Deck.kt           fábrica de disposiciones (6 modos) + GlyphView, PipsView, PadStack, plate()
    TouchpadView.kt   superficie de gestos (RUTA CALIENTE) + PadSink + HaloView + ScrollStripView
    Keys.kt           capa de teclado: CaptureField (IME) + KeyDeck (teclas que el móvil no da)
    MouseClient.kt    socket, coalescencia de movimiento, emisión sin asignaciones
    Discovery.kt      sondeo UDP para encontrar el PC sin escribir IP
    Monet.kt          paleta dinámica (system_* en API 31+, muestreo del fondo por debajo)
    Textures.kt       ruido procedural cacheado + MetalDrawable
    PowerReceiver.kt  abre la app al enchufar (ACTION_POWER_CONNECTED)
server/
  server.py         vigilante de USB + servidor de comandos + instalación del APK
  tray.py           icono de bandeja en Windows con ctypes; fuera de Windows no hace nada
  build-exe.bat     genera UsbMouse.exe portátil
  install-autostart.bat / uninstall-autostart.bat
```

Documentos de diseño en la raíz: `usb-mouse-proporciones.html` (cotas), `usb-mouse-modos.html`
(prototipo interactivo), `usb-mouse-guia.html` (guía de uso).

## Protocolo

Líneas terminadas en `\n`, del móvil al PC. **UTF-8, no ASCII**: el texto de `K` lleva eñes y
signos de apertura. Para todo lo demás son los mismos bytes.

```
M<dx>,<dy>       mover, relativo, float
S<dx>,<dy>       scroll en muescas
C<l|r|m>         click
D<l|r|m>         botón pulsado
U<l|r|m>         botón soltado
K<texto>         escribir texto literal (sin saltos de línea: el Enter va como tecla)
E<tecla>         tecla especial, pulsar y soltar
E+<tecla>        tecla especial, mantener
E-<tecla>        tecla especial, soltar
H<mods>,<tecla>  atajo; mods son letras de c(ctrl) a(alt) s(shift) w(win)
P                keepalive
```

Nombres de tecla: `enter back tab esc space up down left right home end pgup pgdn del ins caps
f1..f20`, o **un solo carácter literal** — `Hc,c` es Ctrl+C. Los que no existan en ese sistema
se ignoran con un aviso en `-v`; no rompen la conexión.

Puerto por defecto 8777. Descubrimiento en UDP 8778: el móvil lanza `USBMOUSE?` a broadcast y
el PC responde `USBMOUSE:<puerto>`.

El servidor suelta solo lo que quedara pulsado al cortarse la línea, teclas incluidas
(`Keys.release_all`), y los modificadores de un atajo se sueltan en orden inverso.

## Reglas de la ruta caliente

Se rompió una vez por esto; no lo deshagas.

- **Nada de `Color.parseColor` dentro de `onDraw`.** Los colores se resuelven a `Int` en
  construcción o en `applyPalette()`.
- **El movimiento del dedo no invalida el cristal.** El halo es `HaloView`, vista aparte con
  capa de hardware que se desplaza con `translationX/Y`. Si vuelves a dibujar el halo dentro de
  `TouchpadView`, vuelve el jank.
- **Cero asignaciones por evento de movimiento.** `MouseClient.emit()` escribe los dígitos a
  mano en un `ByteArray` reutilizado. No metas `String.format` ahí.
- **Nada de lambdas en la ruta caliente.** El pad avisa por `PadSink`, que es una interfaz. Un
  `(Float, Float) -> Unit` es un `Function2<Float, Float, Unit>`, o sea genérico: cada llamada
  encajaría los dos `Float` en objetos, y `Float` no tiene caché como `Integer`. Eran dos
  asignaciones por evento de dedo. Los clics sí son lambdas, que para eso son discretos.
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
- **`PadStack.onMeasure`** impone la relación 1,62, **no `TouchpadView`**. Se rompió una vez
  por esto: con la relación en el cristal, la pila seguía midiendo la columna entera, el cristal
  se quedaba arriba y la banda, anclada al fondo de la pila, se iba al fondo de la pantalla con
  400 dp de deck muerto en medio. El 32 % es del cristal, no de la columna.
- **El deck vacío bajo el cristal es el diseño, no un hueco**: es el reposamuñecas del portátil
  (`usb-mouse-proporciones.html`). Por eso el cuerpo del panel va con `Gravity.TOP` y no
  centrado: lo que sobra tiene que quedar debajo.
- **La cabecera necesita el inset de la barra de estado.** El modo inmersivo solo esconde la de
  navegación; sin bajar el contenido, el LED, los glifos y el engranaje quedan tapados por el
  reloj. Se hace con `setOnApplyWindowInsetsListener` de plataforma, no con androidx.
- **Modo ratón con nivel ≥ 1 apaga `tapToClick` y `twoFingerScroll`** a propósito: con botones
  físicos, un toque en el sensor es un fallo, no una comodidad.
- **Gaming apaga la aceleración** (`usaAccel = accel && tier != 2`). No lo "arregles": `accel`
  es lo que ha pedido el usuario y el modo gaming manda por encima.
- **`Keys.CaptureField` envuelve la `InputConnection`, no escucha el texto.** Es el único sitio
  donde el retroceso llega siempre: según el teclado sale como `KEYCODE_DEL`, como
  `deleteSurroundingText` o como una composición que encoge. Y el campo **guarda** lo escrito en
  vez de vaciarse: si está vacío, muchos teclados no mandan nada al borrar. El `inputType` es
  `VISIBLE_PASSWORD` a propósito, para que no haya autocorrección que adivinar.
- **El campo de captura mide 1×1 px** y vive dentro de la hoja del teclado. Si lo pones `GONE`
  o lo sacas del árbol, deja de recibir el IME.
- **`adb reverse` va del móvil al PC**: el móvil escucha en su `127.0.0.1:8777` y el tráfico
  sale por el cable. El servidor escucha en el `127.0.0.1` del PC. No inviertas la dirección.
- **`PowerReceiver`** solo puede abrir la Activity si está concedido "Mostrar sobre otras apps".
  Sin ese permiso falla en silencio, y es correcto que falle en silencio.
- Cambiar de modo reconstruye `stageHost`, no la Activity: la conexión no debe cortarse.

## Comandos

```bash
# Android (Windows: gradlew.bat). El wrapper se baja Gradle 8.7 la primera vez.
cd android && ./gradlew installDebug
cd android && ./gradlew assembleDebug    # deja el APK donde el vigilante lo busca

# Servidor a mano, con traza de cada comando
cd server && start.bat -v

# Servidor portátil
cd server && build-exe.bat && install-autostart.bat
```

El vigilante instala la app él solo si el móvil no la tiene: busca `server/UsbMouse.apk` y, si
no está, el APK de gradle. Se desactiva con `--no-install`, y la bandeja con `--no-tray`.

### Compilar sin SDK local

`.github/workflows/build-apk.yml` compila el APK en un runner y lo sube como artefacto, más un
trabajo de humo que lo arranca en un emulador. Es la salida cuando el entorno bloquea
`dl.google.com` y no se puede resolver el plugin de Android.

Si ahí tampoco se puede, el Kotlin se puede *revisar* sin SDK: `org.robolectric:android-all`
está en Maven Central y es el framework entero de API 34, así que `kotlinc` con ese jar en el
classpath comprueba los tipos de verdad. No sustituye a una compilación (no hay recursos, ni
fusión de manifiestos, ni D8), pero es lo que destapó que `ScrollStripView` usaba `aspect`.

## Pendiente

- Multimedia: `pynput` puede mandar play/pausa y volumen; faltaría un opcode para ellas.
- Giroscopio como modo puntero.
- El teclado manda `E`/`H` una tecla por línea; para macros largas convendría un opcode que
  acepte una secuencia entera.
