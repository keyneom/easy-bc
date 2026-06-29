# EasyBC privacy policy

Effective: June 21, 2026

EasyBC is a local-first fertility planning application. It does not operate an EasyBC account server, include advertising or analytics SDKs, or sell user data.

## Data stored locally

Period records, day logs, body-signal observations, planner settings, and generated plans are stored on the user's device or in the browser's IndexedDB storage. Android platform backup is disabled so the plaintext application database is not copied through Android Auto Backup.

## Optional Google Drive sync

When a user explicitly enables sync in the web or Android app, EasyBC requests the `https://www.googleapis.com/auth/drive.appdata` scope and creates an application-specific file in Google Drive's hidden app-data folder. User-authored EasyBC data is encrypted on the device before upload using a key derived through the user's passkey. Google receives the encrypted envelope, not the plaintext EasyBC records.

OAuth access tokens and raw passkey PRF output are kept only in memory for the active operation. To support automatic sync without repeated passkey prompts, the derived content-encryption key may remain in browser-tab or Android-process memory for the active app session. It is never intentionally written to browser storage, the Android database, preferences, or backup, and is cleared when the tab/process ends or after 15 minutes in the background. EasyBC uses Google user data only to provide the sync feature requested by the user and does not transfer it to third parties.

## User-directed exports

Manual JSON backups, iCalendar exports, and entries written to a device calendar are not encrypted by EasyBC. Users choose where those files or calendar entries are stored and should protect them as sensitive data.

## Retention and deletion

Local data remains until the user deletes records, clears site/application data, or uninstalls EasyBC. Synced data remains in the Google Drive app-data folder until the user uses **Delete cloud copy** in EasyBC or removes EasyBC's hidden app data through their Google Account. Revoking OAuth access prevents future access but does not by itself guarantee deletion of the stored file.

## Security reports and questions

Report security issues privately through the repository's [security advisory form](https://github.com/keyneom/easy-bc/security/advisories/new). Do not post personal health data in a public issue.
