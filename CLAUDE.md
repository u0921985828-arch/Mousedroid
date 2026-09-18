# USB Mouse

El móvil hace de ratón. Dos transportes detrás de la misma interfaz (`Transport`), con tres
modos de enlace en los ajustes — **Auto**, **Cable**, **Bluetooth**:

- **Cable** — socket TCP → `adb reverse` o anclaje USB → servidor Python con `pynput`. Necesita
  un PC con `server.py` corriendo.
- **Bluetooth HID** — el móvil **se presenta como un ratón y un teclado de verdad**
  (`BluetoothHidDevice`, Android 9+). No hace falta nada al otro lado: vale contra una tele, una
  tablet, una consola o un PC. Y al ser HID real lo ven también los juegos que leen entrada en
  crudo, que con `pynput` se quedaban sin enterarse.

En **Auto** los dos están levantados a la vez: el cable reintenta en bucle y el Bluetooth se
queda anunciado esperando a que alguien empareje. Manda el que esté enganchado, y si lo están los
dos gana el cable — menos latencia y es el único que escribe tildes. El lector de la cabecera
dice cuál ganó (`cable · 127.0.0.1:8777`, `bt · Salón`).

Con eso una sola herramienta cubre los cuatro escenarios: túnel adb, anclaje USB, Wi-Fi y
Bluetooth HID. El campo de destino en `auto` ya prueba el túnel y luego lanza el sondeo UDP, que
encuentra el PC tanto por anclaje como por red.

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

Puerto por defecto 8777. Descubrimiento en UDP 8778, y **solo con `--lan`**: el móvil lanza
`USBMOUSE? <nonce> <firma>` a broadcast y el PC responde `USBMOUSE:<puerto> <firma>`.

## Emparejado y sesión del cable

Por ese socket viajan pulsaciones de teclado, o sea **ejecución de código** en el PC, y además
**tu texto**: contraseñas incluidas. Así que no basta con saber quién llama; hace falta que nadie
por el medio pueda leer ni colar nada.

El código son 12 símbolos (60 bits) de un alfabeto sin `I`, `L`, `O` ni `U`. Vive en
`server/usbmouse-code.txt`, que **nace** con permisos 600 (`os.open`, no `open` + `chmod`: con la
umask había una ventana con el secreto a la vista) y **no se escribe nunca en el registro** —
`install-autostart.bat` arranca con `--log`.

```
PC    -> móvil   U2 <nonceS>
móvil -> PC      A <hmac(K, "S:nonceS:nonceC")> <nonceC>
PC    -> móvil   B <hmac(K, "C:nonceC:nonceS")>
```

`K` sale del código por un **KDF lento**: 200 000 vueltas de HMAC-SHA256 (0,36 s en Python, y se
cachea). Un código de 60 bits usado en crudo como clave HMAC se rompe a martillazos; estirado, no.

**Todo lo que viene después va cifrado y firmado**, con claves de sesión sacadas del apretón —no
del código— y distintas por sentido:

```
<orden 8 hex> <criptograma hex> <firma 16 bytes hex>
```

Flujo con HMAC-SHA256 como PRF (bloque *i* = `HMAC(k_enc, orden ‖ i)`), y encima un HMAC aparte
sobre el criptograma: **cifrar y luego firmar**, en ese orden. El número de orden es estricto —
repetir una línea vieja es volver a pulsar esa tecla.

Esto es lo que cierra al hombre en medio. Con solo el saludo autenticado, quien se colara entre
los dos leía el texto de `K` tal cual y podía inyectar sus propias líneas sin saber el código.

- **Se comprueban los dos**, no solo el móvil.
- **El sondeo UDP no lleva firmas, a propósito.** Firmarlo era regalar un verificador del código
  para romperlo sin límite y sin volver a tocar la red; firmar la respuesta no servía, porque la
  firma no ataba la IP y un intermediario reenviaba el sondeo al PC de verdad para devolver su
  respuesta válida desde su propia dirección. Es una **pista**: se prueban todas las direcciones
  que contesten y decide el apretón.
- **Por USB viaja un vale de un solo uso, no el código.** Los argumentos de `adb` los lee
  cualquier usuario del PC en `/proc/*/cmdline`. El vale caduca en dos minutos, sirve una vez, y
  con él el móvil recoge el código de verdad ya dentro de la sesión cifrada.
- **El móvil pregunta siempre antes de emparejarse.** La Activity es `exported`, así que
  cualquier app sin un solo permiso podía mandar el extra `code`, emparejar el móvil con *su*
  código, escuchar en `127.0.0.1:8777` y recoger todo lo tecleado. Una app no puede pulsar ese
  botón por ti.
- **Dos cupos de conexión**: uno ancho y con plazo corto para los que aún no se han identificado,
  y otro de cuatro para los admitidos. Con un solo cupo, cuatro sockets callados dejaban fuera al
  móvil de verdad.
- Cinco fallos desde una IP y se bloquea un minuto, más medio segundo de castigo por intento.
  **127.0.0.1 nunca se bloquea**: por el túnel de adb todo viene de ahí, así que bloquearla no
  para a un atacante local (ya está dentro) y en cambio deja fuera al móvil.

**Lo que sigue sin cubrir:** por Bluetooth HID la seguridad es la del emparejado de Bluetooth, no
la de aquí. Y `allowBackup` está en `false` desde que hay un secreto guardado.

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
- **En Auto los dos transportes conviven**, y es a propósito: el Bluetooth anunciado no molesta
  mientras nadie empareje, y así no hay que adivinar de antemano qué habrá delante. `link` elige
  en cada llamada; en modo forzado manda el que se haya puesto.
- **La visibilidad Bluetooth no se pide en Auto.** `ACTION_REQUEST_DISCOVERABLE` es un diálogo
  del sistema y sacarlo en cada arranque es intolerable. Solo sale al pedir Bluetooth a propósito
  y cuando aún no hay aparato guardado; para repetirlo está «Hacerme visible 5 min…».
- **`PowerReceiver`** solo puede abrir la Activity si está concedido "Mostrar sobre otras apps".
  Sin ese permiso falla en silencio, y es correcto que falle en silencio.
- Cambiar de modo reconstruye `stageHost`, no la Activity: la conexión no debe cortarse.
  El fundido **quita el anterior en el acto** en vez de desvanecerlo por encima: mientras se
  desvanecía seguía recibiendo el dedo y mandando órdenes por un modo ya abandonado.
- **Pulsar una tecla le da la vuelta a la luz, no la vuelve translúcida.** `MetalDrawable.sink()`
  mueve el filo claro de arriba a abajo e invierte y oscurece el degradado. Era
  `view.alpha = 0.72f`, que es lo que hace una app, no una tecla.
- **El bisel de `WindowDrawable` va al revés que el de `MetalDrawable`**: filo de luz al pie y
  degradado oscuro arriba. Es lo único que distingue un hueco de una pieza montada; si lo
  igualas, el lector deja de leerse como una ventana.
- **`LedView.breathing` es un `postDelayed` a 33 ms** y se para en `onDetachedFromWindow`. Solo
  mueve el alfa de las brochas: recrear el `RadialGradient` por fotograma sí sería ruta caliente.
  `resolver()` termina llamando a `aplicar()` porque asignar `color` pisa el alfa del latido.
- **`DeckIO.haptic(fuerte)` tiene dos golpes a propósito**: 9 ms para un clic, 17 ms y más
  amplitud para el tope de un escalón, que en un ratón de verdad es un diente mecánico.
- **`BLUETOOTH_ADVERTISE` hace falta además de `CONNECT`.** Hacerse visible es *anunciarse*, y
  desde Android 12 eso es otro permiso. Sin él `ACTION_REQUEST_DISCOVERABLE` lanza
  `SecurityException`, el `catch` se la tragaba y el emparejado inicial no podía completarse:
  parecía cosa de la tele.
- **`BtHid` solo acepta el aparato elegido.** Con los cinco minutos de visibilidad, cualquier
  equipo a tiro podía engancharse y quedarse con lo que se tecleara.
- **`arrancarBomba()` espera a que muera el hilo anterior.** Era `if (vivo) return`, y si el
  anfitrión volvía dentro de los 8 ms de sueño del hilo viejo, éste se moría después apagando la
  bandera: quedaba conectado y sin bomba, con el puntero congelado y sin un solo error.
- **Nada se encola sin conexión.** Los clics y teclas guardados mientras no había nadie salían
  de golpe al conectar: hasta 128 de ráfaga contra la tele.
- **`keyOf` devuelve el bit `NEEDS_SHIFT`.** `charOf` ya sabía que `?` es shift+`/`, pero `keyOf`
  tiraba ese dato y se mandaba `/` pelado.
- **El `release` no lleva `signingConfig`.** Estaba firmando con la clave de depuración, que es
  pública y viene con el SDK: cualquiera podía publicar una versión troyanizada que Android
  aceptaba como *actualización*. Mejor un APK sin firmar que una firma que no significa nada.
- **`numero()` rechaza `inf` y `nan`.** Una sola línea `Minf,0` envenenaba el acumulador del
  cursor y el ratón no se movía hasta reconectar.
- **`readline` va con tope y las conexiones también.** Sin el tope, un cliente que no mandara
  nunca un fin de línea tumbaba el servidor por memoria.
- **`BtHid.bomba` es `@Volatile` y se asigna ANTES de `start()`.** El hilo nuevo lee `bomba` nada
  más nacer para saber si sigue siendo el titular; con la asignación después del arranque podía
  leer la vieja y morirse de inmediato — exactamente el puntero congelado que venía a arreglar.
- **`STATE_DISCONNECTED` se filtra por aparato.** Al intruso recién rechazado le llegaba su
  desconexión y se llevaba por delante la sesión legítima.
- **El primero que empareja se queda como el elegido** (`onPrimero`). Sin eso la lista blanca no
  entraba en juego nunca: `btmac` solo lo escribía el diálogo de «Elegir aparato».
- **`BLUETOOTH_ADVERTISE` se pide al hacerse visible, no al arrancar.** Exigirlo de entrada
  dejaba el Bluetooth entero muerto en automático —donde no se usa— si el usuario lo denegaba.
- **`onRequestPermissionsResult` mira todos los resultados**, no `res[0]`: con una petición de
  dos, daba por concedido lo denegado y volvía a preguntar.
- **El aviso de «falta el código» es solo del modo CABLE.** En AUTO se comía los mensajes del
  Bluetooth —«Anunciado. Empareja desde la tele», «Conectado · Salón»— para quejarse de un PC
  que a lo mejor no existe, y el Bluetooth es justo lo que está pasando en ese momento.

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
