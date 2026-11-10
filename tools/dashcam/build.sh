#!/usr/bin/env bash
# Compila la app Dashcam Okavango sin Gradle, con las herramientas del Android SDK.
# Uso: ANDROID_HOME=/ruta/sdk tools/dashcam/build.sh  → tools/dashcam/build/dashcam-okavango.apk
set -euo pipefail
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:?Falta ANDROID_HOME}}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
echo "build-tools: $BT · plataforma: $PLATFORM"
rm -rf build && mkdir -p build/classes build/dex build/libs build/apk/lib

# TensorFlow Lite (detector de carros/personas del aviso de choque), versión fija y verificada.
TFL=2.16.1
fetch() {  # fetch <artefacto> <sha256>
  local f="build/libs/$1-$TFL.aar"
  for base in https://repo1.maven.org/maven2 https://maven-central.storage-download.googleapis.com/maven2; do
    curl -fsSL -o "$f" "$base/org/tensorflow/$1/$TFL/$1-$TFL.aar" && break || true
  done
  echo "$2  $f" | sha256sum -c -
  mkdir -p "build/libs/$1" && (cd "build/libs/$1" && unzip -q "../$1-$TFL.aar")
}
fetch tensorflow-lite-api 885b95bc9e47a4aa5cf8e71523aed9b05e397cfef1ea09773f13fea8b649fcee
fetch tensorflow-lite 34b065817e294e7dd3569504e8b9454938ceae453b6a459da54d5f6e45d3c94e
CP="build/libs/tensorflow-lite-api/classes.jar:build/libs/tensorflow-lite/classes.jar"
for abi in arm64-v8a armeabi-v7a; do
  mkdir -p "build/apk/lib/$abi"
  cp "build/libs/tensorflow-lite/jni/$abi/libtensorflowlite_jni.so" "build/apk/lib/$abi/"
done

"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM/android.jar" --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 22 build/res.zip --java build/gen -A assets
javac -nowarn -encoding UTF-8 -source 8 -target 8 -cp "$PLATFORM/android.jar:$CP" -d build/classes \
  $(find src build/gen -name '*.java')
"$BT/d8" --release --min-api 21 --lib "$PLATFORM/android.jar" --output build/dex \
  $(find build/classes -name '*.class') build/libs/tensorflow-lite-api/classes.jar build/libs/tensorflow-lite/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
(cd build/apk && zip -q -r ../unsigned.apk lib)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
# Llave de prueba fija (no secreta) para que las versiones nuevas se instalen encima de las anteriores.
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -storepass android -keypass android \
    -alias diag -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Dashcam Okavango" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --min-sdk-version 21 --out build/dashcam-okavango.apk build/aligned.apk
"$BT/apksigner" verify --verbose build/dashcam-okavango.apk | head -5
ls -la build/dashcam-okavango.apk
