#!/bin/bash
set -e

DO_UNINSTALL=false
if [ "$1" == "--clean" ]; then
  DO_UNINSTALL=true
fi

PKG="io.mrarm.irc.dev"
FLAVOR="Debug"      # adjust if needed
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

if $DO_UNINSTALL; then
  echo "==> Uninstalling $PKG…"
  adb uninstall "$PKG" || true
else
  echo "==> Skipping uninstall."
fi

echo "==> Building $FLAVOR…"
./gradlew assemble${FLAVOR}

echo "==> Installing APK…"
adb install -r "$APK_PATH"

echo "==> Launching app…"
adb shell am start -n "${PKG}/io.mrarm.irc.MainActivity"

echo "==> Done."
