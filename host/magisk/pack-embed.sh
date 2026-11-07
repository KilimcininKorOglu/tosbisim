#!/usr/bin/env bash
# Pack the Magisk module WITHOUT the APK, as an asset of the host app: the app installs it itself once the phone
# is rooted (customize.sh copies the installed APK into the module). Run before building the app.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
DAEMONS="$HERE/../daemon/out"
ABIS="arm64-v8a armeabi-v7a"
OUT="$HERE/../android/app/src/embed/assets/module"
for abi in $ABIS; do [ -f "$DAEMONS/$abi/hermessimd" ] || { echo "build host/daemon first ($abi)"; exit 1; }; done
STAGE="$(mktemp -d)"
cp -R "$HERE/template/." "$STAGE/"
# One daemon per ABI under bin/<abi>/: customize.sh keeps the phone's own, Updater swaps in the same one
for abi in $ABIS; do mkdir -p "$STAGE/bin/$abi" && cp "$DAEMONS/$abi/hermessimd" "$STAGE/bin/$abi/hermessimd"; done
mkdir -p "$OUT"
rm -f "$OUT/hermessim-host-module.zip"
(cd "$STAGE" && zip -qr -X "$OUT/hermessim-host-module.zip" .)
rm -rf "$STAGE"
echo "embedded module: $OUT/hermessim-host-module.zip ($(wc -c < "$OUT/hermessim-host-module.zip") bytes)"
