# Android shell (Jetpack Compose)

**Gradle + Compose** mirroring the web JSON API. Native code is built from `planner-core` as `libplanner_core.so`.

## Local

1. **JDK 17** + Android SDK. From `android/`: `./gradlew assembleDebug` (Gradle wrapper is committed).
2. Native: set `ANDROID_NDK_HOME`, install [`cargo-ndk`](https://github.com/bbqsrc/cargo-ndk), then run [`scripts/android-build-native.sh`](../scripts/android-build-native.sh) to populate `app/src/main/jniLibs/`.
3. Run `uniffi-bindgen` (see [`MOBILE_FFI.md`](../crates/planner-core/MOBILE_FFI.md)) and replace [`PlannerCoreBridge.kt`](app/src/main/java/com/easybc/planner/PlannerCoreBridge.kt) with generated Kotlin that calls `planFertilityRiskPlannerJson`.

CI assembles the debug APK with `./gradlew assembleDebug` (no NDK required for that step).

## Features implemented

### Persistence & migration safety
- Room database with **explicit migrations** (`MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` in [`AppDatabase.kt`](app/src/main/java/com/easybc/planner/data/db/AppDatabase.kt)). **No** `fallbackToDestructiveMigration()` — a future schema bump without a matching migration crashes loudly on first open rather than silently wiping user data.
- Schema blueprints exported to `app/schemas/com.easybc.planner.data.db.AppDatabase/{1,2,3,4}.json` for regression detection.
- Manifest has `android:allowBackup="true"`, so Android Auto Backup covers reinstall and new-device handoff via Google Drive.

### Native device calendar sync
[`EasyBCCalendarSync`](app/src/main/java/com/easybc/planner/calendar/EasyBCCalendarSync.kt) creates a **local-only** Android calendar using `CalendarContract.ACCOUNT_TYPE_LOCAL`, so nothing reaches a remote server. Writes one composite all-day event per day, joining period / fertile / plan-action components with ` + ` (e.g. `P + C`, `P + A`, bare `A`). Event description is the bare integer risk score for plan-action days, otherwise mirrors the title — nothing a bystander can read reveals meaning.

[`CalendarAutoSync`](app/src/main/java/com/easybc/planner/calendar/CalendarAutoSync.kt) is an application-scoped observer on `combine(settingsFlow, periodsFlow, dayLogsFlow, plannerResultFlow)`, debounced at 1.5s, that calls into `syncEvents` whenever the user has enabled auto-sync and the calendar permission is held. Errors are logged and swallowed — auto-sync never blocks the UI.

Event labels are user-editable from Settings (defaults: `P / F / U / C / A / W`) for additional bystander privacy.

**Note for Google Calendar users:** local-only calendars are hidden by default in the Google Calendar app. Users need to enable "EasyBC Planner" in Google Calendar → menu → Settings. The Settings screen shows a hint under the sync toggle reminding them.

### Backup / restore
[`DataBackup`](app/src/main/java/com/easybc/planner/io/DataBackup.kt) is a versioned JSON export/import of settings + periods + day logs, written/read via the Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). The export is the cross-device migration path when users switch phones — no account, no server. DTOs mirror Room entities explicitly, so DB schema changes don't silently break backup compatibility.

### Period-end prediction
[`CycleCalculator.effectiveBleedingEndEpochDay`](app/src/main/java/com/easybc/planner/util/CycleCalculator.kt) predicts the remaining bleeding days for an unresolved (open) period instead of capping at today. Uses the user's mean closed-period duration with ≥ 3 samples, otherwise a 5-day default, capped only at the day before the next logged period start. Web mirror: [`derivedBleedingEnd`](../web/src/tracker/cyclePhase.ts).

Cycle length is measured start-to-start, so this change does not affect ovulation prediction, fertile window, or plan actions — only the painted bleeding-range.
