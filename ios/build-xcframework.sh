#!/usr/bin/env bash
# Produce planner_core.xcframework for iOS device + simulator (Apple Silicon sim).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios
LIB_NAME=planner_core
OUT="$ROOT/ios/Frameworks"
rm -rf "$OUT/$LIB_NAME.xcframework"
mkdir -p "$OUT"

cargo build -p planner-core --features ffi --release --target aarch64-apple-ios
cargo build -p planner-core --features ffi --release --target aarch64-apple-ios-sim

DEVICE_LIB="target/aarch64-apple-ios/release/lib${LIB_NAME}.a"
SIM_LIB="target/aarch64-apple-ios-sim/release/lib${LIB_NAME}.a"

xcodebuild -create-xcframework \
  -library "$DEVICE_LIB" \
  -library "$SIM_LIB" \
  -output "$OUT/${LIB_NAME}.xcframework"

echo "XCFramework at $OUT/${LIB_NAME}.xcframework"
echo "Then: uniffi-bindgen generate --library target/aarch64-apple-ios/release/lib${LIB_NAME}.dylib --language swift -o ios/EasyBC/Generated"
