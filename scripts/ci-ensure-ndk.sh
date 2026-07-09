#!/usr/bin/env bash
# Ensure the Android NDK pinned in build.gradle.kts is installed on a CI runner.
# Prefers the runner image's preinstalled copy; falls back to sdkmanager with
# retries (dl.google.com downloads truncate often enough to fail whole runs).
# Exports ANDROID_NDK_HOME to $GITHUB_ENV for later workflow steps.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK_VERSION="$(sed -n 's/^[[:space:]]*ndkVersion = "\([^"]*\)"/\1/p' "$ROOT/android/app/build.gradle.kts")"
test -n "$NDK_VERSION"
NDK_DIR="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"

if [[ -d "$NDK_DIR" ]]; then
  echo "Using preinstalled NDK at $NDK_DIR"
else
  for attempt in 1 2 3; do
    if "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" "ndk;$NDK_VERSION" && [[ -d "$NDK_DIR" ]]; then
      break
    fi
    rm -rf "$NDK_DIR"
    if [[ "$attempt" -eq 3 ]]; then
      echo "Failed to install ndk;$NDK_VERSION after $attempt attempts" >&2
      exit 1
    fi
    echo "sdkmanager attempt $attempt failed; retrying" >&2
    sleep 15
  done
fi

echo "ANDROID_NDK_HOME=$NDK_DIR" >> "$GITHUB_ENV"
