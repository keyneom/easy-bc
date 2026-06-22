# GitHub Pages and passkey identity

The web app is deployed as a GitHub project site at:

`https://keyneom.github.io/easy-bc/`

The public repository deploys `web/dist` directly with GitHub's Pages artifact flow. No cross-repository token or deploy key is required. While the repository is private, the workflow still runs its build and test gates but skips the Pages-only steps.

Every project site under `keyneom.github.io` shares the same browser origin, including IndexedDB and the WebAuthn relying-party identity. EasyBC intentionally accepts that boundary for this deployment: another application served from the same host must therefore be treated as equally trusted. URL paths do not provide browser-storage or passkey isolation.

## Google Drive encrypted sync

The web build reads a public OAuth web client ID from the GitHub Actions variable `GOOGLE_WEB_CLIENT_ID` (or `VITE_GOOGLE_WEB_CLIENT_ID` for local development). Configure `https://keyneom.github.io` as an authorized JavaScript origin. The distinct Android OAuth client ID is retained as `GOOGLE_ANDROID_CLIENT_ID`. Client IDs are public configuration, not secrets, and no OAuth client secret belongs in this repository.

Sync requests only `https://www.googleapis.com/auth/drive.appdata`. EasyBC creates one hidden `easybc-sync-v1.json` file in Drive's app-data folder. The file contains a versioned AES-GCM envelope; planner settings, period records, and user-entered logs are adaptively gzip-compressed and then encrypted on the device before upload. OAuth tokens and passkey material are never included in that payload. Original uncompressed v1 snapshots remain readable.

The passkey is scoped to the final relying-party host. WebAuthn PRF output is expanded with HKDF-SHA-256 into the AES-256-GCM key for each sync operation. The raw cloud key is never persisted. Localhost creates a separate development passkey and cannot unlock the production snapshot.

WebAuthn uses the origin host—not the project path—as its relying-party identity. Android uses the same `keyneom.github.io` relying-party identity through Credential Manager. The root Pages site must therefore publish `https://keyneom.github.io/.well-known/assetlinks.json` with `delegate_permission/common.get_login_creds`, package `com.easybc.planner`, and the SHA-256 fingerprint of the release signing certificate. That file belongs in the separate `keyneom/keyneom.github.io` repository because a project site cannot publish host-root `/.well-known` content.

The Android OAuth client must use package `com.easybc.planner` and the same release signing certificate registered with Google Cloud. Android's Authorization API selects that client from package/signature identity; no client secret or client ID is embedded in the APK. The separately named `GOOGLE_ANDROID_CLIENT_ID` repository variable exists to keep the two registered clients auditable and prevent accidental replacement by the web client.

## Future domain migration

A later hostname change creates a new WebAuthn RP identity and therefore a new passkey. This does not require losing local data: an existing device can use **Replace passkey and cloud copy** to upload its local canonical data under a new passkey. The old origin must remain available until that migration is complete.
