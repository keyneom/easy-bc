# Mobile FFI (uniFFI)

The `planner-core` crate exposes JSON in/out when built with `--features ffi`.

## Build the native library

```bash
# macOS (debug dylib)
cargo build -p planner-core --features ffi

# Android (example; use cargo-ndk or your NDK toolchain)
rustup target add aarch64-linux-android
cargo build -p planner-core --features ffi --release --target aarch64-linux-android
```

Release artifacts:

- macOS: `target/release/libplanner_core.dylib`
- Linux: `target/release/libplanner_core.so`
- Android: `target/aarch64-linux-android/release/libplanner_core.so`
- iOS: build `staticlib` with `cargo build --features ffi --target aarch64-apple-ios` (and simulator target as needed), then wrap in an **XCFramework**.

## Exported API (proc-macro)

- `plan_fertility_risk_planner_json(opts_json: String) -> Result<String, String>` — same payload as WASM `planFertilityRiskJson`.

Implementation: [`src/lib.rs`](src/lib.rs) with `#[uniffi::export]` and `uniffi::setup_scaffolding!()`.

## Generate Kotlin / Swift bindings

Install the toolchain that matches this crate’s **uniFFI 0.28** line (see `Cargo.toml`):

```bash
cargo install uniffi_bindgen --version 0.28.3
```

Then (library path must match your platform):

```bash
uniffi-bindgen generate --library ../../target/release/libplanner_core.dylib \
  --language kotlin \
  -o ../../bindings/kotlin

uniffi-bindgen generate --library ../../target/release/libplanner_core.dylib \
  --language swift \
  -o ../../bindings/swift
```

Point `--library` at the built **cdylib** / shared object produced above. Review generated crate loading (`libplanner_core`) on Android (`System.loadLibrary`) and Swift (XCFramework module).

## JSON schema

Input: serde **`UserOptions`** (camelCase in JSON). Output: **`PlannerResult`**.

Optional fields include `calendarCycles`, `realizedCumulativeRisk`, and `initialActionLocks` (see Rust types).
