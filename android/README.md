# Android shell (Jetpack Compose)

Shippable skeleton: **Gradle + Compose** mirroring the web JSON API. Native code is built from `planner-core` as `libplanner_core.so`.

## Local

1. **JDK 17** + Android SDK. From `android/`: `./gradlew assembleDebug` (Gradle wrapper is committed).
2. Native: set `ANDROID_NDK_HOME`, install [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk), then run [`scripts/android-build-native.sh`](../scripts/android-build-native.sh) to populate `app/src/main/jniLibs/`.
3. Run `uniffi-bindgen` (see [`MOBILE_FFI.md`](../crates/planner-core/MOBILE_FFI.md)) and replace [`PlannerCoreBridge.kt`](app/src/main/java/com/easybc/planner/PlannerCoreBridge.kt) with generated Kotlin that calls `planFertilityRiskPlannerJson`.

[`MainActivity.kt`](app/src/main/java/com/easybc/planner/MainActivity.kt) is a minimal JSON field + button until the JNI/uniFFI surface is wired.

CI assembles the debug APK with `./gradlew assembleDebug` (no NDK required for that step).
