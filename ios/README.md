# iOS shell (SwiftUI)

Build a **static** `libplanner_core.a` (and device/simulator slices), wrap in an **XCFramework**, then run uniFFI Swift binding generation — see [`crates/planner-core/MOBILE_FFI.md`](../crates/planner-core/MOBILE_FFI.md).

## Outline

1. Run `./build-xcframework.sh` from this directory (wraps device + simulator `staticlib` archives).
2. `uniffi-bindgen generate --library … --language swift -o EasyBC/Generated` (see [`MOBILE_FFI.md`](../crates/planner-core/MOBILE_FFI.md)).
3. Add the XCFramework + generated Swift to an Xcode app target; replace the placeholder in `EasyBC/ContentView.swift` with the exported `planFertilityRiskPlannerJson`.

`EasyBC/ContentView.swift` is a minimal JSON-in / JSON-out UI until bindings are linked.

Keep reproductive data on-device only.
