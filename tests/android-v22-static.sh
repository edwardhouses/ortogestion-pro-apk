#!/usr/bin/env bash
set -euo pipefail
ROOT="${1:-source/OrtoGestion-Pro-Android}"
MAIN="$ROOT/app/src/main/java/com/ortogestion/pro/MainActivity.kt"
BRIDGE="$ROOT/app/src/main/java/com/ortogestion/pro/OrtoNubeBridge.kt"
MANIFEST="$ROOT/app/src/main/AndroidManifest.xml"
GRADLE="$ROOT/app/build.gradle.kts"
HTML="$ROOT/app/src/main/assets/www/index.html"

for file in "$MAIN" "$BRIDGE" "$MANIFEST" "$GRADLE" "$HTML"; do
  test -s "$file" || { echo "Falta archivo requerido: $file"; exit 1; }
done

grep -q 'android.permission.CAMERA' "$MANIFEST"
grep -q 'RESOURCE_VIDEO_CAPTURE' "$MAIN"
grep -q 'appassets.androidplatform.net' "$MAIN"
grep -q 'OrtoNube' "$MAIN"
grep -q 'AuthorizationRequest' "$BRIDGE"
grep -q 'drive.file' "$BRIDGE"
grep -q 'uploadType=resumable' "$BRIDGE"
grep -q 'window.__ortoNubeGuardar' "$BRIDGE"
grep -q 'window.__ortoNubeLista' "$BRIDGE"
grep -q 'window.__ortoNubeDescarga' "$BRIDGE"
grep -q 'window.__ortoNubeBorrado' "$BRIDGE"
grep -q 'versionCode = 22' "$GRADLE"
grep -q 'versionName = "2.2.0"' "$GRADLE"
grep -q 'play-services-auth:22.0.0' "$GRADLE"
grep -q 'window.__ortoNubeConectado' "$HTML"
grep -q 'getUserMedia' "$HTML"

if grep -Eq 'refresh_token|PONER_CLIENT_ID_AQUI|oauth2callback' "$BRIDGE"; then
  echo "El puente contiene un patrón OAuth antiguo o credenciales placeholder."
  exit 1
fi

echo "Android v2.2 static integration checks passed."
