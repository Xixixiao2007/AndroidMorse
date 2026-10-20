#!/usr/bin/env bash
# Build the debug APK and put it on the user's WSA (Windows Subsystem for Android).
# Usage: tools/deploy-wsa.sh [--no-build]
set -euo pipefail

export JAVA_HOME='C:\Program Files (x86)\Android\openjdk\jdk-17.0.14'
export ANDROID_HOME='C:\Program Files (x86)\Android\android-sdk'
export ANDROID_SDK_ROOT="$ANDROID_HOME"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ADB_TARGET="127.0.0.1:58526"
APK="app/build/outputs/apk/debug/app-debug.apk"

if [[ "${1:-}" != "--no-build" ]]; then
  echo "== build =="
  cmd.exe //c "C:\DSHproj\AndroidMorse\tools\gradle-8.9\bin\gradle.bat --no-daemon -q -Pkotlin.incremental=false assembleDebug"
fi

# WSA gets suspended/closed often; (re)start it if adb cannot see it.
if ! adb devices | grep -q "58526.*device"; then
  echo "== starting WSA =="
  powershell.exe -NoProfile -Command "Start-Process 'shell:AppsFolder\MicrosoftCorporationII.WindowsSubsystemForAndroid_8wekyb3d8bbwe!App'"
  for _ in $(seq 1 30); do
    adb connect "$ADB_TARGET" >/dev/null 2>&1 || true
    if adb devices | grep -q "58526.*device"; then break; fi
    sleep 2
  done
fi

echo "== install =="
adb -s "$ADB_TARGET" install -r "$APK"

echo "== launch =="
adb -s "$ADB_TARGET" shell am start -W -n com.bh4gzk.morserunner/.MainActivity
