#!/usr/bin/env bash
# Build libplanner_core.so for Android arm64 and copy into the Compose app's jniLibs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
: "${ANDROID_NDK_HOME:=${ANDROID_NDK_LATEST_HOME:-}}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to your NDK (e.g. $HOME/Library/Android/sdk/ndk/26.1.10909125)}"
export ANDROID_NDK_HOME
rustup target add aarch64-linux-android
cargo ndk -t arm64-v8a -o "$ROOT/android/app/src/main/jniLibs" build -p planner-core --release --features ffi
echo "Copied .so — regenerate bindings when the FFI changes; see crates/planner-core/MOBILE_FFI.md"
