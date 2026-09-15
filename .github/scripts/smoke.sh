#!/usr/bin/env bash
# Prueba de humo en el emulador: instalar, abrir y comprobar que sigue viva.
#
# Esto va en un fichero y no en el `script:` del workflow porque
# reactivecircus/android-emulator-runner ejecuta ese campo LINEA A LINEA, cada
# una en su propio `sh -c`. Un if/then/fi repartido en varias lineas se parte y
# muere con "Syntax error: end of file unexpected".
set -uo pipefail

APK="${1:-UsbMouse.apk}"
ACT="com.eddie.usbmouse/.MainActivity"

echo "== instalando $APK"
adb install -r "$APK" || { echo "!! no se pudo instalar"; exit 1; }

adb logcat -c
echo "== abriendo $ACT"
adb shell am start -n "$ACT" || { echo "!! am start fallo"; exit 1; }

# margen para que arranque, dibuje y el bucle de conexion de varias vueltas en
# vacio (en el emulador no hay servidor, asi que reintenta y no debe caerse)
sleep 15

adb exec-out screencap -p > pantallazo.png
echo "== pantallazo: $(wc -c < pantallazo.png) bytes"

echo "== logcat de la app"
adb logcat -d | grep -iE "usbmouse|AndroidRuntime" | tail -40

fallo=0

if adb logcat -d | grep -q 'FATAL EXCEPTION'; then
    echo "!! la app se ha caido:"
    adb logcat -d | grep -A 30 'FATAL EXCEPTION'
    fallo=1
fi

# "Displayed <activity>" solo lo escribe el sistema cuando el primer fotograma
# ya esta en pantalla: es la prueba de que dibujo, no solo de que arranco.
if adb logcat -d | grep -q "Displayed com.eddie.usbmouse/.MainActivity"; then
    echo "ok: la Activity llego a dibujarse"
else
    echo "!! no hay 'Displayed' en el log: no llego a pintar"
    fallo=1
fi

if adb shell dumpsys activity activities | grep -q "com.eddie.usbmouse/.MainActivity"; then
    echo "ok: la Activity sigue arriba a los 15 s"
else
    echo "!! la Activity ya no esta arriba"
    fallo=1
fi

exit "$fallo"
