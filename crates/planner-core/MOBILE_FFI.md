# Android FFI (uniFFI)

The `planner-core` crate exposes JSON in/out when built with `--features ffi`.

## Build the native library

Run these commands from the repository root:

```bash
# macOS release dylib
cargo build -p planner-core --features ffi --release

# Android arm64 (requires cargo-ndk 4.1.2 and NDK 27.0.12077973)
ANDROID_NDK_HOME=/path/to/android-ndk ./scripts/android-build-native.sh
```

Release artifacts:

- macOS: `target/release/libplanner_core.dylib`
- Linux: `target/release/libplanner_core.so`
- Android: `target/aarch64-linux-android/release/libplanner_core.so`

## Exported API (proc-macro)

- `plan_fertility_risk_planner_json(opts_json: String) -> Result<String, String>` — same payload as WASM `planFertilityRiskJson`.

Implementation: [`src/lib.rs`](src/lib.rs) with `#[uniffi::export]` and `uniffi::setup_scaffolding!()`.

## Generate Kotlin bindings

Use the workspace binding generator, which is locked to the same UniFFI dependency graph. The library path must match the build platform:

```bash
cargo run -p uniffi-bindgen -- generate --library target/release/libplanner_core.dylib \
    --language kotlin \
    -o android/app/src/main/java
```

Point `--library` at the built **cdylib** / shared object produced above. Review generated crate loading (`libplanner_core`) on Android (`System.loadLibrary`).

## JSON schema

Input: serde **`UserOptions`** (camelCase in JSON). Output: **`PlannerResult`**.

Optional fields include `calendarCycles`, `realizedCumulativeRisk`, and `initialActionLocks` (see Rust types).
