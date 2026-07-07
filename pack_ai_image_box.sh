#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
cd "$ROOT"

PROPS_WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.properties"
GRADLE_PROPS="$ROOT/gradle.properties"

bash "$ROOT/gradle/wrapper/ensure-gradle-zip.sh"

PROPS_BAK=""
GRADLE_PROPS_BAK=""
restore_props() {
  if [[ -n "$PROPS_BAK" && -f "$PROPS_BAK" ]]; then
    mv -f "$PROPS_BAK" "$PROPS_WRAPPER"
  fi
  if [[ -n "$GRADLE_PROPS_BAK" && -f "$GRADLE_PROPS_BAK" ]]; then
    mv -f "$GRADLE_PROPS_BAK" "$GRADLE_PROPS"
  fi
}
trap restore_props EXIT

ZIP="$ROOT/gradle/wrapper/gradle-8.9-bin.zip"
if [[ -f "$ZIP" ]]; then
  PROPS_BAK="$(mktemp)"
  cp "$PROPS_WRAPPER" "$PROPS_BAK"
  FILE_URI="file://$(realpath "$ZIP")"
  sed -i "s|^distributionUrl=.*|distributionUrl=${FILE_URI//\//\\/}|" "$PROPS_WRAPPER"
  sed -i 's/^validateDistributionUrl=.*/validateDistributionUrl=false/' "$PROPS_WRAPPER"
fi

chmod +x "$ROOT/gradlew" 2>/dev/null || true

find_local_aapt2() {
  if [[ -d "${ANDROID_HOME}/build-tools" ]]; then
    local build_tools_dir aapt2_path
    for build_tools_dir in $(ls -1 "${ANDROID_HOME}/build-tools" 2>/dev/null | sort -V -r); do
      aapt2_path="${ANDROID_HOME}/build-tools/${build_tools_dir}/aapt2"
      if [[ -x "$aapt2_path" ]] && file "$aapt2_path" 2>/dev/null | grep -qE 'aarch64|ARM'; then
        echo "$aapt2_path"
        return 0
      fi
    done
  fi
  local candidate
  for candidate in /data/data/com.termux/files/usr/bin/aapt2 "${PREFIX:-}/bin/aapt2"; do
    if [[ -x "$candidate" ]]; then
      echo "$candidate"
      return 0
    fi
  done
  return 1
}

AAPT2="$(find_local_aapt2 || true)"
if [[ -z "$AAPT2" ]]; then
  echo "pack_ai_image_box: no executable aapt2 found" >&2
  exit 1
fi
echo "pack_ai_image_box: aapt2=$AAPT2"

while IFS= read -r -d '' bad; do
  rm -rf "$(dirname "$(dirname "$bad")")" 2>/dev/null || true
done < <(find "${HOME}/.gradle/caches" -path '*/transformed/aapt2-*/aapt2' -print0 2>/dev/null)

GRADLE_PROPS_BAK="$(mktemp)"
cp "$GRADLE_PROPS" "$GRADLE_PROPS_BAK"
if grep -q '^android.aapt2FromMavenOverride=' "$GRADLE_PROPS"; then
  sed -i "s|^android.aapt2FromMavenOverride=.*|android.aapt2FromMavenOverride=$AAPT2|" "$GRADLE_PROPS"
else
  printf '\n# pack_ai_image_box.sh only, restored on exit; do not commit\nandroid.aapt2FromMavenOverride=%s\n' "$AAPT2" >> "$GRADLE_PROPS"
fi

./gradlew assembleDebug --no-daemon

OUT="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
VERSION_NAME="$(grep versionName "$ROOT/app/build.gradle.kts" | head -1 | sed 's/.*"\(.*\)".*/\1/')"
DEST="$(dirname "$ROOT")/AIImageBox_${VERSION_NAME}_debug.apk"
cp -f "$OUT" "$DEST"
ls -lh "$OUT" "$DEST"
aapt dump badging "$OUT" 2>/dev/null | head -3 || true

