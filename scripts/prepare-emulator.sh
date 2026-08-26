#!/usr/bin/env bash
# Prepare un emulateur pour les tests instrumentes de Sillon :
# installe AnkiDroid, accorde les permissions, verifie que la collection existe.
#
# Usage : bash scripts/prepare-emulator.sh [serial]
set -euo pipefail

SERIAL="${1:-emulator-5554}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
ADB="$ANDROID_HOME/platform-tools/adb"
VERSION="2.24.0"
WORK="${TMPDIR:-/tmp}/sillon-emulator"
mkdir -p "$WORK"

abi="$("$ADB" -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')"
case "$abi" in
  x86_64)      asset="variant-abi-AnkiDroid-$VERSION-x86_64.apk" ;;
  arm64-v8a)   asset="AnkiDroid-$VERSION-arm64-v8a.apk" ;;
  armeabi-v7a) asset="variant-abi-AnkiDroid-$VERSION-armeabi-v7a.apk" ;;
  *)           asset="AnkiDroid-$VERSION-full-universal.apk" ;;
esac
echo "== ABI $abi -> $asset =="

if ! "$ADB" -s "$SERIAL" shell pm list packages | grep -q com.ichi2.anki; then
  url="https://github.com/ankidroid/Anki-Android/releases/download/v$VERSION/$asset"
  echo "== telechargement d'AnkiDroid =="
  curl -sSL -o "$WORK/$asset" "$url"
  echo "== installation =="
  "$ADB" -s "$SERIAL" install -r "$WORK/$asset"
else
  echo "== AnkiDroid deja installe =="
fi

echo "== permissions de Sillon =="
for perm in \
  android.permission.RECORD_AUDIO \
  android.permission.POST_NOTIFICATIONS \
  com.ichi2.anki.permission.READ_WRITE_DATABASE
do
  "$ADB" -s "$SERIAL" shell pm grant fr.appprepa.app "$perm" 2>/dev/null \
    || echo "  (ignore : $perm, l'application n'est peut-etre pas encore installee)"
done

# AnkiDroid ne cree sa collection qu'au premier lancement, et son ecran d'accueil
# exige l'acces a tous les fichiers avant de laisser passer.
echo "== acces au stockage pour AnkiDroid =="
"$ADB" -s "$SERIAL" shell appops set com.ichi2.anki MANAGE_EXTERNAL_STORAGE allow || true

echo "== premier lancement =="
"$ADB" -s "$SERIAL" shell monkey -p com.ichi2.anki -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
sleep 12

if "$ADB" -s "$SERIAL" shell ls /storage/emulated/0/AnkiDroid/collection.anki2 >/dev/null 2>&1; then
  echo "== collection presente =="
else
  echo "!! collection absente : ouvre AnkiDroid a la main une fois, puis relance ce script"
  exit 1
fi
