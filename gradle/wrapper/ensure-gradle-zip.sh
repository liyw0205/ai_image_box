#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ZIP="$ROOT/gradle/wrapper/gradle-8.9-bin.zip"
URL="https://services.gradle.org/distributions/gradle-8.9-bin.zip"

if [[ -f "$ZIP" ]]; then
  echo "Gradle zip exists: $ZIP"
  exit 0
fi

for candidate in \
  "$ROOT/../gradle-8.9-bin.zip" \
  "$ROOT/../MediaVault_git/gradle/wrapper/gradle-8.9-bin.zip" \
  "$ROOT/../MediaVault/gradle/wrapper/gradle-8.9-bin.zip"; do
  if [[ -f "$candidate" ]]; then
    mkdir -p "$(dirname "$ZIP")"
    cp -f "$candidate" "$ZIP"
    echo "Copied Gradle zip from: $candidate"
    ls -lh "$ZIP"
    exit 0
  fi
done

echo "Downloading Gradle 8.9 -> $ZIP"
mkdir -p "$(dirname "$ZIP")"
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 --connect-timeout 30 -o "$ZIP" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$ZIP" "$URL"
else
  echo "curl or wget is required" >&2
  exit 1
fi
ls -lh "$ZIP"

