#!/bin/bash
# Dibuja la interfaz a PNG con el Skia de verdad. Ver README.md.
#
#   JARS=/ruta/con/los/jars tools/render/render.sh /tmp/shot
#
set -e
AQUI=$(cd "$(dirname "$0")" && pwd)
APP=$(cd "$AQUI/../../android/app/src/main/java/com/eddie/usbmouse" && pwd)
JARS=${JARS:-$AQUI/jars}
OUT=${1:-/tmp/shot}
TMP=${TMPDIR:-/tmp}/usbmouse-render

AA=$(ls "$JARS"/android-all-*.jar | head -1)
STD=$(ls "$JARS"/kotlin-stdlib-*.jar | head -1)
KOTLINC=$(ls "$JARS"/kotlin-compiler-*.jar "$JARS"/kotlin-reflect-*.jar \
             "$JARS"/kotlin-script-runtime-*.jar "$JARS"/trove4j-*.jar \
             "$JARS"/annotations-13*.jar 2>/dev/null | tr '\n' ':')
ROB=$(ls "$JARS"/*.jar | grep -vE "android-all|kotlin-|trove4j|annotations-13" | tr '\n' ':')

# 1. los sustitutos de androidx.test (solo vive en el Maven de Google)
rm -rf "$TMP" && mkdir -p "$TMP/ax" "$TMP/cls"
javac -nowarn -cp "$AA" -d "$TMP/ax" $(find "$AQUI/axstub" -name '*.java')

# 2. la app + el arnes
java -cp "$KOTLINC$STD" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -nowarn -jvm-target 17 -d "$TMP/cls" \
  -classpath "$AA:$STD:$ROB$TMP/ax" \
  "$APP"/*.kt "$AQUI/Shot.kt" 2>&1 | grep -E "^e:|error:" && exit 1

# 3. a dibujar
rm -rf "$OUT"
java -Drobolectric.offline=true -Drobolectric.usePreinstrumentedJars=false \
  -Drobolectric.dependency.dir="$JARS" -Dshot.out="$OUT" -Djava.awt.headless=true \
  -cp "$TMP/cls:$TMP/ax:$STD:$ROB$AA" \
  org.junit.runner.JUnitCore com.eddie.usbmouse.Shot
