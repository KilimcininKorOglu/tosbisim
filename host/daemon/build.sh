#!/usr/bin/env bash
# Build hermessimd for Android with the NDK + CMake, once per ABI: 64-bit ARM, and 32-bit ARM for phones whose Android
# is 32-bit (cheap phones, old ones on custom ROMs). Output: host/daemon/out/<abi>/hermessimd; the Magisk module carries
# both and keeps the phone's own at install (customize.sh).
# Fetches pinned libopus + tinyalsa sources (deps.sh) and compiles them in.
set -eu
cd "$(dirname "$0")"
./deps.sh
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
CMAKE="$(ls -d "$SDK"/cmake/*/bin/cmake 2>/dev/null | sort -V | tail -1 || true)"
CMAKE="${CMAKE:-cmake}"
NINJA="$(dirname "$CMAKE")/ninja"
[ -x "$NINJA" ] || NINJA=ninja
READELF="$NDK/toolchains/llvm/prebuilt/$(ls "$NDK/toolchains/llvm/prebuilt" | head -1)/bin/llvm-readelf"
for ABI in arm64-v8a armeabi-v7a; do
  "$CMAKE" -S . -B "build/$ABI" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=29 \
    -DCMAKE_MAKE_PROGRAM="$NINJA" >/dev/null
  out=$("$CMAKE" --build "build/$ABI" --target hermessimd 2>&1) || { echo "$out" | grep -E "error|Error" ; echo "BUILD FAILED ($ABI)"; exit 1; }
  mkdir -p "out/$ABI" && cp "build/$ABI/hermessimd" "out/$ABI/hermessimd"
  # Every loadable segment 16 KB-aligned (Android 15+ 16 KB-page kernels won't run anything less)
  for a in $("$READELF" -lW "out/$ABI/hermessimd" | awk '$1 == "LOAD" { print $NF }'); do
    [ $((a)) -ge 16384 ] || { echo "hermessimd ($ABI): a segment is aligned to $a, below 16 KB"; exit 1; }
  done
  echo "built out/$ABI/hermessimd"
done
