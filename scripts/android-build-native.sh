#!/usr/bin/env bash
# Build libplanner_core.so for Android arm64 and copy into the Compose app's jniLibs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK (e.g. $HOME/Library/Android/sdk/ndk/26.1.10909125)}"
rustup target add aarch64-linux-android
cargo ndk -t arm64-v8a -o "$ROOT/android/app/src/main/jniLibs" build -p planner-core --release --features ffi
echo "Copied .so — run: uniffi-bindgen generate --library <built-so> --language kotlin -o android/app/src/main/java"
