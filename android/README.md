# Android shell (Jetpack Compose)

**Gradle + Compose** mirroring the web JSON API. Native code is built from `planner-core` as `libplanner_core.so`.

## Local

1. **JDK 17** + Android SDK. The Gradle wrapper is committed.
2. Native: set `ANDROID_NDK_HOME`, install `cargo-ndk` 4.1.2, then run [`scripts/android-build-native.sh`](../scripts/android-build-native.sh) to populate the ignored `app/src/main/jniLibs/` build output.
3. Regenerate the Kotlin bindings with the workspace `uniffi-bindgen` helper when the FFI surface changes; see [`MOBILE_FFI.md`](../crates/planner-core/MOBILE_FFI.md). The generated binding is [`uniffi/planner_core/planner_core.kt`](app/src/main/java/uniffi/planner_core/planner_core.kt), wrapped by [`PlannerBridge.kt`](app/src/main/java/com/easybc/planner/bridge/PlannerBridge.kt).
4. From `android/`, run `./gradlew assembleDebug`.

CI and release jobs always rebuild the native library from the checked-in Rust source before Gradle packages an APK.

## Release signing

Tagged releases are built by [the release workflow](../.github/workflows/release-android.yml). The workflow expects these secrets in the protected GitHub `release` environment:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The keystore is decoded only into the temporary Actions runner. Keep a separate encrypted backup of the keystore and credentials; GitHub does not allow secret values to be recovered after upload, and Android updates must use the same signing identity.

## Features implemented

### Persistence & migration safety
- Room database with **explicit migrations** through `MIGRATION_6_7` in [`AppDatabase.kt`](app/src/main/java/com/easybc/planner/data/db/AppDatabase.kt). **No** `fallbackToDestructiveMigration()` — a future schema bump without a matching migration crashes loudly on first open rather than silently wiping user data.
- Schema blueprints are exported through version 7 for regression detection.
- Android Auto Backup and device-transfer backup are disabled so the plaintext Room database is not copied to cloud backup. Android users move data explicitly with the backup/export flow.

### Encrypted cross-device sync

The Settings screen can opt into the same encrypted Google Drive snapshot used by the web app. Google Authorization requests only `drive.appdata`; Credential Manager evaluates the `keyneom.github.io` passkey PRF; HKDF-SHA-256 derives an AES-256-GCM content key. Tokens and derived key material are not persisted. Device-permission toggles such as calendar and notification enablement remain local, while planner settings, labels, periods, and user-entered day logs merge by timestamp.

Android passkeys require the host-root Digital Asset Links association described in [`docs/github-pages-passkeys.md`](../docs/github-pages-passkeys.md). Production testing must use the release-signed APK; a debug-signed APK has a different certificate and must not replace an installed release build.

### Native device calendar sync
[`EasyBCCalendarSync`](app/src/main/java/com/easybc/planner/calendar/EasyBCCalendarSync.kt) creates a **local-only** Android calendar using `CalendarContract.ACCOUNT_TYPE_LOCAL`, so nothing reaches a remote server. Writes one composite all-day event per day, joining period / fertile / plan-action components with ` + ` (e.g. `P + C`, `P + A`, bare `A`). Event description is the bare integer risk score for plan-action days, otherwise mirrors the title — nothing a bystander can read reveals meaning.

[`CalendarAutoSync`](app/src/main/java/com/easybc/planner/calendar/CalendarAutoSync.kt) is an application-scoped observer on `combine(settingsFlow, periodsFlow, dayLogsFlow, plannerResultFlow)`, debounced at 1.5s, that calls into `syncEvents` whenever the user has enabled auto-sync and the calendar permission is held. Errors are swallowed so auto-sync never blocks the UI; diagnostic stack traces are emitted only in debug builds.

Event labels are user-editable from Settings (defaults: `P / F / U / C / A / W`) for additional bystander privacy.

**Note for Google Calendar users:** local-only calendars are hidden by default in the Google Calendar app. Users need to enable "EasyBC Planner" in Google Calendar → menu → Settings. The Settings screen shows a hint under the sync toggle reminding them.

### Backup / restore
[`DataBackup`](app/src/main/java/com/easybc/planner/io/DataBackup.kt) is a versioned JSON export/import of settings + periods + day logs, written/read via the Storage Access Framework (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`). The export is the cross-device migration path when users switch phones — no account, no server. DTOs mirror Room entities explicitly, so DB schema changes don't silently break backup compatibility.

Backup JSON is intentionally portable and therefore **not encrypted**. The Settings UI warns users to protect exported files. Imports validate before replacing data and apply all database changes in one Room transaction.

### Period-end prediction
[`CycleCalculator.effectiveBleedingEndEpochDay`](app/src/main/java/com/easybc/planner/util/CycleCalculator.kt) predicts the remaining bleeding days for an unresolved (open) period instead of capping at today. Uses the user's mean closed-period duration with ≥ 3 samples, otherwise a 5-day default, capped only at the day before the next logged period start. Web mirror: [`derivedBleedingEnd`](../web/src/tracker/cyclePhase.ts).

Cycle length is measured start-to-start, so this change does not affect ovulation prediction, fertile window, or plan actions — only the painted bleeding-range.
