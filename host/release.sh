#!/usr/bin/env bash
# Build a release of TosbiSim Host: daemon -> embedded Magisk module -> signed APK -> the Magisk zip, in host/out/.
# Users install ONLY the Magisk zip (20 Oct 2026: Play Protect blocks the sideloaded APK); the APK itself is what
# hosts download for over-the-air self-updates (--publish). Needs the release key in ~/.tosbisim.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# --publish: hosts get it over the air from hermessim.keremgok.tr. --github: the source to the public repo (publish-public.sh),
# then a GitHub release there with the Magisk zip, the file users download (tosbisim.com links to
# releases/latest/download/TosbiSim-Host-Magisk.zip).
PUBLISH=0; GITHUB=0
for a in "$@"; do case "$a" in --publish) PUBLISH=1;; --github) GITHUB=1;; *) echo "unknown option $a"; exit 2;; esac; done
STAGE=""; GHSTAGE=""
trap 'rm -rf "$STAGE" "$GHSTAGE"' EXIT
VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' "$HERE/android/app/build.gradle.kts")
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$HERE/android/app/build.gradle.kts")
REPO=KilimcininKorOglu/tosbisim; TAG="host-v$VER"

# Every check before anything is built or published: a release that stops half-way must not have gone out half-way
# (--publish used to push the OTA build before the GitHub check could refuse it; audit, 20 Oct 2026).
[ -f "$HOME/.tosbisim/signing.properties" ] || { echo "no release key in ~/.tosbisim"; exit 1; }
[ -f "$HERE/changelog.json" ] || { echo "no host/changelog.json"; exit 1; }
if [ "$PUBLISH" = 1 ] || [ "$GITHUB" = 1 ]; then
  # What is published is exactly a commit: its hash goes in the notes
  [ -z "$(git -C "$ROOT" status --porcelain -- host protocol)" ] || {
    echo "uncommitted changes under host/ or protocol/: commit them first"; git -C "$ROOT" status --short -- host protocol; exit 1; }
fi
COMMIT=$(git -C "$ROOT" rev-parse --short HEAD)
if [ "$PUBLISH" = 1 ]; then
  # The release token lives outside this script, which is published in a public repo:
  # ~/.hermessim/release.env sets HERMESSIM_RELEASE_TOKEN and optionally HERMESSIM_URL.
  ENV="$HOME/.hermessim/release.env"
  [ -f "$ENV" ] || { echo "no $ENV (HERMESSIM_RELEASE_TOKEN=...)"; exit 1; }
  # shellcheck disable=SC1090
  . "$ENV"
  [ -n "${HERMESSIM_RELEASE_TOKEN:-}" ] || { echo "$ENV must set HERMESSIM_RELEASE_TOKEN"; exit 1; }
  HERMESSIM_URL="${HERMESSIM_URL:-https://hermessim.keremgok.tr}"
  HERMESSIM_URL="${HERMESSIM_URL%/}"
  command -v curl >/dev/null || { echo "no curl"; exit 1; }
fi
if [ "$GITHUB" = 1 ]; then
  command -v gh >/dev/null || { echo "no gh"; exit 1; }
  if out=$(gh release view "$TAG" --repo "$REPO" 2>&1); then echo "GitHub release $TAG exists already"; exit 1; fi
  echo "$out" | grep -qi "not found" || { echo "gh release view $TAG: $out"; exit 1; }
fi

# Every release ships with notes in English and Turkish (host/changelog.json): no notes, no release.
NOTES="$HERE/out/notes-$VER.json"
mkdir -p "$HERE/out"
python3 - "$HERE/changelog.json" "$VER" "$CODE" "$NOTES" <<'PY' || { echo "add $VER ($CODE) with en + tr to host/changelog.json"; exit 1; }
import json, sys
log, ver, code, out = sys.argv[1], sys.argv[2], int(sys.argv[3]), sys.argv[4]
e = next(x for x in json.load(open(log, encoding="utf-8")) if x["version"] == ver and x["code"] == code)
assert e["en"].strip() and e["tr"].strip()
json.dump({"en": e["en"], "tr": e["tr"]}, open(out, "w", encoding="utf-8"), ensure_ascii=False)
PY
"$HERE/daemon/build.sh"
"$HERE/magisk/pack-embed.sh"
(cd "$HERE/android" && ./gradlew -q assembleRelease)
OUT="$HERE/out/TosbiSim-Host-$VER.apk"
cp "$HERE/android/app/build/outputs/apk/release/app-release.apk" "$OUT"
APKSIGNER="$(ls "$HOME"/Library/Android/sdk/build-tools/*/apksigner | sort -V | tail -1)"
"$APKSIGNER" verify --print-certs "$OUT" | grep -m1 "SHA-256"
# Hosts install an update only when it has their app's signer: an APK signed with any other key would reach no one
# (or, as a fresh Magisk install, start a second line of hosts that never update again). Stop here instead.
CERT=08dafcad5f23c49dd44997fc036182fa7659273a500499b2c4c33879d4344d54
"$APKSIGNER" verify --print-certs "$OUT" | grep -q "certificate SHA-256 digest: $CERT" \
  || { echo "$OUT is not signed with the TosbiSim release key: not released"; exit 1; }
(cd "$HERE/out" && shasum -a 256 "$(basename "$OUT")" > "$(basename "$OUT").sha256")
echo "release: $OUT ($COMMIT)"

# The same host as a Magisk module that carries the app: installed from the Magisk app (Modules -> Install from
# storage) it puts TosbiSim on as a system app, which Play Protect's sideloading block never sees. That block stops the
# plain APK cold on some phones ("App blocked to protect your device", no way past it: SMS permissions; Redmi Note 8
# Pro, 20 Oct 2026). customize.sh uses the APK it finds in the module.
ZIP="$HERE/out/TosbiSim-Host-$VER-Magisk.zip"
STAGE="$(mktemp -d)"
unzip -q "$HERE/android/app/src/embed/assets/module/tosbisim-host-module.zip" -d "$STAGE"
mkdir -p "$STAGE/system/priv-app/SimRelayHost"
cp "$OUT" "$STAGE/system/priv-app/SimRelayHost/SimRelayHost.apk"
sed -i '' "s/^version=.*/version=$VER/; s/^versionCode=.*/versionCode=$CODE/" "$STAGE/module.prop"
rm -f "$ZIP"
(cd "$STAGE" && zip -qr -X "$ZIP" .)
(cd "$HERE/out" && shasum -a 256 "$(basename "$ZIP")" > "$(basename "$ZIP").sha256")
echo "magisk: $ZIP"

# --github, first half: a draft (invisible to users) with both assets, checked before anything goes public.
# The asset name never changes, so "latest" always serves the newest zip.
if [ "$GITHUB" = 1 ]; then
  # The source first, and the release's tag on the public commit that holds it: published after the release (as until
  # 1 Nov 2026), the tag landed on the previous version's source.
  "$ROOT/tools/publish-public.sh" "Host $VER"
  PUBDIR="${TOSBISIM_PUBLIC_DIR:-$ROOT/../tosbisim-public}"
  PUBSHA=$(git -C "$PUBDIR" rev-parse HEAD)
  git -C "$PUBDIR" show "HEAD:host/android/app/build.gradle.kts" | grep -q "versionName = \"$VER\"" \
    || { echo "the public repo's last commit isn't the source of $VER: not released"; exit 1; }
  gh api "repos/$REPO/commits/$PUBSHA" --jq .sha >/dev/null || { echo "public commit $PUBSHA isn't on GitHub: not released"; exit 1; }
  GHSTAGE="$(mktemp -d)"
  cp "$ZIP" "$GHSTAGE/TosbiSim-Host-Magisk.zip"
  (cd "$GHSTAGE" && shasum -a 256 TosbiSim-Host-Magisk.zip > TosbiSim-Host-Magisk.zip.sha256)
  python3 - "$NOTES" "$GHSTAGE/notes.md" "https://github.com/$REPO/tree/$PUBSHA" <<'PYN'
import json, sys
n = json.load(open(sys.argv[1], encoding="utf-8"))
open(sys.argv[2], "w", encoding="utf-8").write(
    n["en"] + "\n\n**Install:** in Magisk, Modules → Install from storage → TosbiSim-Host-Magisk.zip → Reboot. "
    "Guide: https://tosbisim.com\n\n---\n\n" + n["tr"] +
    "\n\n**Kurulum:** Magisk → Modüller → Depolamadan yükle → TosbiSim-Host-Magisk.zip → Yeniden başlat. Rehber: https://tosbisim.com\n"
    "\n---\n\nSource: " + sys.argv[3] + "\n")
PYN
  gh release create "$TAG" --repo "$REPO" --target "$PUBSHA" --title "TosbiSim Host $VER" --notes-file "$GHSTAGE/notes.md" --draft \
    "$GHSTAGE/TosbiSim-Host-Magisk.zip" "$GHSTAGE/TosbiSim-Host-Magisk.zip.sha256"
  assets=$(gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[].name')
  for f in TosbiSim-Host-Magisk.zip TosbiSim-Host-Magisk.zip.sha256; do
    echo "$assets" | grep -qx "$f" || { echo "draft $TAG lacks $f: not published (fix or delete the draft)"; exit 1; }
  done
fi

# --publish: hand it to hermessim.keremgok.tr, where hosts pick it up (they ask the phone before installing).
if [ "$PUBLISH" = 1 ]; then
  NOTES_EN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["en"])' "$NOTES")
  NOTES_TR=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1], encoding="utf-8"))["tr"])' "$NOTES")
  curl -fsS -X POST "$HERMESSIM_URL/api/v1/releases/publish" \
    -H "Authorization: Bearer $HERMESSIM_RELEASE_TOKEN" \
    --form-string "platform=host" \
    --form-string "version=$VER" \
    --form-string "code=$CODE" \
    --form-string "notes_en=$NOTES_EN" \
    --form-string "notes_tr=$NOTES_TR" \
    -F "apk=@$OUT" >/dev/null
  echo "ota: $VER ($CODE) published to $HERMESSIM_URL"
fi

# --github, second half: the checked draft goes public as the latest release.
if [ "$GITHUB" = 1 ]; then
  gh release edit "$TAG" --repo "$REPO" --draft=false --latest >/dev/null
  echo "github: https://github.com/$REPO/releases/tag/$TAG"
fi
