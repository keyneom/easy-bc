Place ABI-specific shared objects here after building `planner-core` with the Android NDK, for example:

- `arm64-v8a/libplanner_core.so`
- `x86_64/libplanner_core.so` (emulator)

See [`scripts/android-build-native.sh`](../../scripts/android-build-native.sh) and [`crates/planner-core/MOBILE_FFI.md`](../../crates/planner-core/MOBILE_FFI.md).
