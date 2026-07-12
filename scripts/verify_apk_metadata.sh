#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$ROOT/app/build/outputs/apk/debug/app-debug.apk}"
AAPT="${AAPT:-$(command -v aapt || true)}"
if [[ -z "$AAPT" || ! -x "$AAPT" ]]; then
  echo "verify_apk_metadata: aapt is required" >&2
  exit 1
fi
if [[ ! -f "$APK" ]]; then
  echo "verify_apk_metadata: APK not found: $APK" >&2
  exit 1
fi

expected_package="$(sed -n "s/.*applicationId = \"\(.*\)\".*/\1/p" "$ROOT/app/build.gradle.kts" | head -1)"
expected_version_name="$(sed -n "s/.*versionName = \"\(.*\)\".*/\1/p" "$ROOT/app/build.gradle.kts" | head -1)"
expected_version_code="$(sed -n "s/.*versionCode = \([0-9][0-9]*\).*/\1/p" "$ROOT/app/build.gradle.kts" | head -1)"
badging="$($AAPT dump badging "$APK")"
actual_package="$(printf "%s\n" "$badging" | sed -n "s/package: name='\([^' ]*\)'.*/\1/p" | head -1)"
actual_version_code="$(printf "%s\n" "$badging" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
actual_version_name="$(printf "%s\n" "$badging" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"

[[ "$actual_package" == "$expected_package" ]] || { echo "package mismatch: $actual_package != $expected_package" >&2; exit 1; }
[[ "$actual_version_code" == "$expected_version_code" ]] || { echo "versionCode mismatch: $actual_version_code != $expected_version_code" >&2; exit 1; }
[[ "$actual_version_name" == "$expected_version_name" ]] || { echo "versionName mismatch: $actual_version_name != $expected_version_name" >&2; exit 1; }
printf "APK verified: %s versionCode=%s versionName=%s\n" "$actual_package" "$actual_version_code" "$actual_version_name"
