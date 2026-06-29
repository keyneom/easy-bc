# Security policy

## Reporting a vulnerability

Please report suspected vulnerabilities through [GitHub private vulnerability reporting](https://github.com/keyneom/easy-bc/security/advisories/new). Do not include secrets, reproductive-health data, or exploit details in a public issue.

If a credential may have been exposed, revoke or rotate it before sending a report. Include the affected version, impact, and a minimal reproduction when possible.

## Supported versions

Security fixes are applied to the latest published web build and Android release. Older APKs are not maintained after an update is available.

## Data-handling boundary

EasyBC is local-first. The app has no analytics or advertising SDK. Manual JSON and calendar exports are user-controlled plaintext files. Optional web and Android Google Drive sync encrypts its payload before upload and requests only the Drive app-data scope. Raw passkey output is operation-scoped; a short-lived Google access token and the derived content key may remain only in tab or app-process memory for automatic sync and are cleared when that session ends or after 15 minutes in the background.
