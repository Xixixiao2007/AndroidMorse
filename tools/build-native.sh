#!/usr/bin/env bash
# Cross-build the two native pieces of the app.
#
#   1. core-pascal/  ->  libmrcore.so   (Free Pascal aarch64 cross-compiler)
#   2. jni/mrcore_jni.c -> libmorsejni.so (Android NDK clang)
#
# Both land in app/src/main/jniLibs/arm64-v8a/, which is what Gradle packages into the APK.
# These are the exact flags the maintainer builds with; override the tool paths with env vars.
#
#   FPC      Free Pascal aarch64 cross compiler   (default: ppcrossa64 on PATH)
#   NDK      Android NDK root                     (default: $ANDROID_NDK_HOME)
#   NDK_HOST prebuilt host directory              (default: windows-x86_64 on Windows,
#                                                  linux-x86_64 elsewhere)
#   NDK_BIN  the 4.9 toolchain bin dir            (default: aarch64-linux-android-4.9/prebuilt/$NDK_HOST/bin)
#
# Why the odd NDK invocation: the shim is compiled freestanding (-nostdlib) against the sysroot and
# links the Pascal core as a plain shared library, so no C++ runtime is pulled in and libmrcore.so
# is loaded by the Java side first (see app/src/main/kotlin/.../MorseCore.kt).
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
FPC=${FPC:-ppcrossa64}
NDK=${NDK:-${ANDROID_NDK_HOME:-}}

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST_DEFAULT=windows-x86_64 ;;
  *)                    HOST_DEFAULT=linux-x86_64 ;;
esac
NDK_HOST=${NDK_HOST:-$HOST_DEFAULT}
NDK_BIN=${NDK_BIN:-$NDK/toolchains/aarch64-linux-android-4.9/prebuilt/$NDK_HOST/bin}

mkdir -p "$OUT"

echo "== Pascal engine =="
( cd "$ROOT/core-pascal" &&
  "$FPC" -Tandroid -Paarch64 -Mdelphi -Scghi -gl -vewnhibq \
         -Fuheadless -FuUtil -olibmrcore.so mrcore.lpr )
cp "$ROOT/core-pascal/libmrcore.so" "$OUT/libmrcore.so"

if [ -z "$NDK" ]; then
  echo "NDK not set: skipping the JNI shim (set ANDROID_NDK_HOME to build it)" >&2
  exit 0
fi

echo "== JNI shim =="
"$NDK/toolchains/llvm/prebuilt/$NDK_HOST/bin/clang" \
  --target=aarch64-linux-android \
  -B"$NDK_BIN" \
  -fPIC -shared -nostdlib --sysroot="$NDK/sysroot" \
  -o "$OUT/libmorsejni.so" "$ROOT/jni/mrcore_jni.c" \
  -L"$OUT" -lmrcore

echo "done: $OUT/libmrcore.so $OUT/libmorsejni.so"
