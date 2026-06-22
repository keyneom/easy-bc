CI and release builds place ignored ABI-specific shared objects here after building `planner-core` from source, for example:

- `arm64-v8a/libplanner_core.so`

See [`scripts/android-build-native.sh`](../../../../../scripts/android-build-native.sh) and [`crates/planner-core/MOBILE_FFI.md`](../../../../../crates/planner-core/MOBILE_FFI.md).
