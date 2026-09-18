# Mirar la interfaz sin móvil

Renderiza las mismas clases que van al APK a PNG, con el Skia de verdad
(`@GraphicsMode(NATIVE)` de Robolectric). No es un emulador —no hay recursos ni
D8— pero **dibuja píxeles**, que es lo único que sirve para juzgar un diseño.

Destapó cuatro defectos que el compilador no puede ver:

- las esquinas macro de gaming no anidaban con el radio del cristal y se
  apilaban encima de la banda de clic;
- la banda de clic eran tres pastillas de radio 11 flotando dentro de un
  cristal de radio 14, con muescas entre ellas;
- el hueco del lector salía marrón `(41,38,31)` dentro de una chapa fría
  `(42,45,47)`, y encima igual de claro que ella: no se leía como hundido;
- el engranaje de ajustes era invisible, y es el único camino a los ajustes.

## Qué hace falta

Los jars no están en el repo (184 MB). De Maven Central:

    org.robolectric:android-all:14-robolectric-10818077
    org.robolectric:robolectric:4.12.2 (+ sus módulos y dependencias)
    org.conscrypt:conscrypt-openjdk-uber:2.5.2
    junit:junit:4.13.2
    org.jetbrains.kotlin:kotlin-compiler:1.9.24

`axstub/` son sustitutos mínimos de `androidx.test`, que solo vive en el Maven
de Google. Robolectric lo toca en cuatro sitios; están todos cubiertos.

## Uso

    tools/render/render.sh /tmp/shot

Deja un PNG por modo (`raton-*`, `panel-*`), otro de la Activity entera con su
cabecera, y las dos hojas de abajo (ajustes y teclado), más las cotas de cada
pieza en dp para comprobar números y no solo el ojo.
