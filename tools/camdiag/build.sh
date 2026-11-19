#!/usr/bin/env bash
# Compila el APK de diagnóstico de cámaras sin Gradle, con las herramientas del Android SDK.
# Uso: ANDROID_HOME=/ruta/sdk tools/camdiag/build.sh  → tools/camdiag/build/diagnostico-camaras.apk
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Falta ANDROID_HOME}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
echo "build-tools: $BT · plataforma: $PLATFORM"
rm -rf build && mkdir -p build/classes build/dex
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM/android.jar" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 22 build/res.zip --java build/gen
javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$PLATFORM/android.jar" -d build/classes \
  $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$PLATFORM/android.jar" --output build/dex \
  $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Llave de prueba fija (no secreta) para que las versiones nuevas se instalen encima de las anteriores.
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias diag -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Diagnostico Camaras" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 21 --out build/diagnostico-camaras.apk build/aligned.apk
"$BT/apksigner" verify --verbose build/diagnostico-camaras.apk | head -5
ls -la build/diagnostico-camaras.apk
