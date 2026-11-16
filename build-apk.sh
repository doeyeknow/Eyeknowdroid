#!/bin/bash
# Manual APK assembly for the eyeKnow native shell.
#
# Gradle cannot run in this sandbox (the runtime garbles JVM IPv4
# loopback sockets, which kills the Gradle daemon handshake), so
# the APK is assembled with the Android SDK tools directly:
# aapt2 -> javac -> d8 -> zipalign -> apksigner. All inputs are
# local files: framework android.jar plus this project's Java
# sources and resources. No third-party dependencies at all.
set -euo pipefail
cd "$(dirname "$0")"
source ./env.sh
BT="$ANDROID_HOME/build-tools/34.0.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"
B="build/manual"
rm -rf "$B"
mkdir -p "$B/flata" "$B/gen" "$B/classes" "$B/dex"

echo "== compile resources"
"$BT/aapt2" compile --dir res -o "$B/flata/resources.flata"

echo "== link resources + manifest"
"$BT/aapt2" link -o "$B/eyeknow-unsigned.apk" -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml --java "$B/gen" \
  --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name "1.0" \
  "$B/flata/resources.flata"

echo "== javac"
find src "$B/gen" -name "*.java" > "$B/sources.txt"
javac -encoding UTF-8 --release 17 \
  -cp "$ANDROID_JAR" -d "$B/classes" @"$B/sources.txt"

echo "== d8"
(cd "$B/classes" && jar --create --file ../classes.jar .)
"$BT/d8" --min-api 26 --lib "$ANDROID_JAR" --output "$B/dex" "$B/classes.jar"

echo "== package + align"
(cd "$B/dex" && zip -q "../eyeknow-unsigned.apk" classes*.dex)
"$BT/zipalign" -f -p 4 "$B/eyeknow-unsigned.apk" "$B/eyeknow-aligned.apk"

echo "== signing key"
if [ ! -f eyeknow-release.keystore ]; then
  PASS=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')
  keytool -genkeypair -keystore eyeknow-release.keystore -alias eyeknow \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" \
    -dname "CN=eyeKnow, O=eyeKnow, C=US" > /dev/null 2>&1
  printf 'keystore: eyeknow-release.keystore\nalias: eyeknow\npassword: %s\n' "$PASS" \
    > keystore-credentials.txt
  chmod 600 keystore-credentials.txt eyeknow-release.keystore
  echo "new eyeKnow release keystore created"
fi
PASS=$(grep '^password:' keystore-credentials.txt | cut -d' ' -f2)
"$BT/apksigner" sign --ks eyeknow-release.keystore \
  --ks-pass "pass:$PASS" --key-pass "pass:$PASS" \
  --out "$B/eyeknow.apk" "$B/eyeknow-aligned.apk"
cp "$B/eyeknow.apk" ./eyeknow.apk

echo "== verify"
"$BT/apksigner" verify --print-certs ./eyeknow.apk > "$B/apksigner.txt"
sed -n '1,5p' "$B/apksigner.txt"
"$BT/aapt2" dump badging ./eyeknow.apk > "$B/badging.txt"
sed -n '1,12p' "$B/badging.txt"
ls -lh ./eyeknow.apk
echo BUILD-OK
