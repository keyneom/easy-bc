# Android multi-profile calendar projection (Phase 2)

Web encrypted sync can expose multiple profiles (owned + shared). Android will mirror
the same **device-local calendar projection** model once shared profile data is
available on device (sync-kit Android `/sharing` port).

## Naming

Calendar display names follow Drive folder naming:

```text
EasyBC — owner@example.com
```

Kotlin helper: [`EasyBcCalendarNames.kt`](../android/app/src/main/java/com/easybc/planner/calendar/EasyBcCalendarNames.kt)

## Model

- **Encrypted sync role** controls Drive publish authority (owner / writer / viewer).
- **Calendar projection** is a separate per-profile device toggle (never in encrypted payload).
- **Viewers** may project read-only shared cycles to a local calendar without write access to the encrypted backup.

## Planned work

1. Refactor [`EasyBCCalendarSync`](../android/app/src/main/java/com/easybc/planner/calendar/EasyBCCalendarSync.kt) to create/find calendars by `(displayName, internalName)` pair instead of a single fixed `"EasyBC Planner"` calendar.
2. Store per-profile projection prefs in Room metadata (`profileKey → { enabled, calendarId }`).
3. Extend [`CalendarAutoSync`](../android/app/src/main/java/com/easybc/planner/calendar/CalendarAutoSync.kt) to refresh **all enabled profile projections**, not only the active UI profile.
4. Settings UI: per-profile “Show on device calendar” toggle under profile switcher.

## Current status

Shared encrypted sync is implemented on Android (`com.easybc.planner.sync.shared`):
setup, sync, invite, join via deep link, profile switching, and autosync for writable
profiles. Sharing identity private keys are stored in EncryptedSharedPreferences
(Android Keystore). Account-binding attestation for web-owner acceptance of Android
join responses is still a follow-up.

Per-profile calendar projection (this document) remains Phase 2: default calendar
name is still `EasyBC Planner` until projection toggles land.
