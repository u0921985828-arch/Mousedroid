# USB Mouse

El móvil hace de ratón. Dos transportes, elegibles en los ajustes, detrás de la misma interfaz
(`Transport`):

- **Cable** — socket TCP → `adb reverse` o anclaje USB → servidor Python con `pynput`. Necesita
  un PC con `server.py` corriendo.
- **Bluetooth HID** — el móvil **se presenta como un ratón y un teclado de verdad**
  (`BluetoothHidDevice`, Android 9+). No hace falta nada al otro lado: vale contra una tele, una
  tablet, una consola o un PC. Y al ser HID real lo ven también los juegos que leen entrada en
  crudo, que con `pynput` se quedaban sin enterarse.

Sin root, sin internet en tiempo de ejecución.

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
    Transport.kt      la interfaz que comparten los dos transportes
    MouseClient.kt    transporte por cable: socket, coalescencia, emisión sin asignaciones
    BtHid.kt          transporte Bluetooth: descriptor HID, registro del perfil, informes
    HidKeys.kt        protocolo -> códigos HID (USB HID Usage Tables, página 0x07)
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

Los tonos de `Monet`, **en todas las versiones**, en HSL sobre el tono semilla. Del sistema se
toma el tono y nada más: `system_neutral1_900` (chapa) y `system_neutral2_900` (cristal) son los
dos el tono 10 de Material You y salían separados por 2 de 255, así que la banda de clic
desaparecía dentro del cristal. Que la separación sea siempre ésta es lo que hace que las piezas
se distingan en cualquier móvil.

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
- **En el panel, el cristal se lleva la columna entera.** `usb-mouse-proporciones.html` describe
  el deck vacío de debajo como el reposamuñecas de un portátil, pero eso vale cuando el cristal
  ocupa el 40 % del ancho del chasis; aquí ocupa el 100 %, así que el aparato es un trackpad
  externo y no el hueco recortado de un portátil. En un móvil 2,2:1 aquello dejaba el 60 % de la
  pantalla muerta. La relación 1,62 sigue siendo el **suelo** (`PadStack.onMeasure` con
  `aspect > 0`, que usa el modo ratón), no un techo.
- **La banda de clic tiene techo**: 32 % del alto, pero como mucho el 32 % de un cristal de 1,62.
  En un trackpad de proporción real las dos cifras coinciden; con el cristal estirado la banda
  conserva su profundidad en vez de comerse un tercio de toda la superficie. Nunca baja de 48 dp.
- **Banda y esquinas se dimensionan en `onMeasure`, no en `onSizeChanged`.** Mutando el
  `LayoutParams` sin `setLayoutParams` y antes de medir a los hijos. Estaba en `onSizeChanged`,
  que llega con el layout ya en marcha: pedía otra pasada de medida desde dentro de la anterior y
  la banda se quedaba con el alto de 1 px con el que nace. Se veía como si no existiera.
- **El panel en gaming tiene cuatro esquinas macro** (G1–G4, 26 % × 19 %), como en
  `usb-mouse-modos.html`. Van por encima de la banda: el pulgar las alcanza sin levantar la mano.
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
- **El descriptor HID de `BtHid` no se toca a ojo.** Es el descriptor de combo estándar (ratón
  con tres botones y rueda en el informe 1, teclado de seis teclas en el 2). Un byte mal puesto
  deja el aparato mudo sin decir por qué, porque el anfitrión simplemente ignora el informe.
- **Un teclado HID manda posiciones de tecla, no letras.** La distribución la pone el equipo del
  otro lado, así que `HidKeys.charOf` supone un anfitrión en US y las tildes y la eñe se quedan
  fuera; el lector lo avisa. Por cable esto no pasaba: `pynput` escribía el texto tal cual. Es lo
  que se paga por ser un periférico de verdad en vez de un programa.
- **Los dos transportes no conviven**: al encender el Bluetooth se cierra el socket, y al
  apagarlo se rearranca el bucle de reconexión por cable.
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
