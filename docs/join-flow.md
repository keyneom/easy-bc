# Join, grant & response: the guided flow

Spec for the cross-account sharing journey, written 2026-07-10. Replaces the
Toast-and-go-find-Settings behavior with guided screens, puts **all
authentication before the browser hand-off**, and makes both links land on
the exact screen that handles them. Protocol pieces are sync-kit rc.11
(link-carried exchange + Picker file grants); this doc is app UX + sequencing.

The one legally-unavoidable manual step is the Google Picker file selection
(`drive.file` grants are per-file and require the user to pick). Everything
else is automatic.

## Links (all HTTPS app links on `keyneom.github.io/easy-bc/`)

| Link | Params | Opened by | Lands on |
| --- | --- | --- | --- |
| Join link | `sk-inv` (signed invitation) + `sk-files` (dataset files) + `owner` (email) | joiner | **Join screen** (app) / join flow (web) |
| Grant hand-off | join params + `grant-files=1` | joiner's browser (Custom Tab, opened by the app) | **Grant page** (web, dedicated — implemented in `web/src/ui/GrantAccessScreen.tsx`) |
| Return link | join params + `sk-granted=1` (grant marker stripped) | browser → app via `intent://…;package=com.easybc.planner` with https fallback | **Join screen**, auto-continues |
| Response link | `sk-resp=1` + `sk-kr` (signed key response) | owner | **Accept screen** (app/web), auto-processes |

## The authentication rule

> **Interactive authentication (Google authorization + passkey unlock) happens
> exactly once per flow, at a moment the user expects it, and always _before_
> the sync-kit operations that need it.** Background auto-sync must never
> compete with a link-driven flow for the auth UI.

Mechanics (Android):

1. `InteractiveAuthGate` (`sync/InteractiveAuthGate.kt`, implemented) —
   a single-flight mutex for every interactive auth/passkey sequence plus a
   `deepLinkFlowActive` flag.
2. `MainActivity` marks the flag **before** `CloudAutoSyncSession.start()`
   whenever the launching intent carries `sk-inv` / `sk-resp` / `sk-granted`;
   clearing a pending link (`PendingSharedJoin.clear*`) releases it.
3. `CloudAutoSyncSession` (wired) calls `awaitNoDeepLinkFlow()` before
   acquiring a token and takes the gate for its own interactive auth — so the
   startup/foreground auto-sync waits its turn instead of stacking a second
   Google/passkey prompt on top of the join.
4. Every user-driven flow (join, accept, invite) runs its
   authorize+unlock+operate sequence inside `InteractiveAuthGate.run { … }`
   (wiring point: the `authorizeAnd*` helpers in `SettingsScreen`/
   `SettingsViewModel`).
5. The join screen performs auth at the **Continue** tap — labeled
   "Confirming it's you…" — *before* opening the browser. When the user
   returns from the Picker, tokens and the unlocked sharing identity are
   still warm in memory, so the join + response generation runs with **zero
   further prompts**. If the process was killed while the browser was open,
   the gate runs the auth again on return — as a visible step, never mid-spinner.

## Joiner journey (Android)

```
tap join link
  → app opens JOIN screen (nav route "join-flow", not a Toast)
      shows: owner email · profile name · the files being shared (labels + your role)
      CTA: “Continue”
  → [1. auth] gate.run { Google authorize + passkey unlock/create }   “Confirming it's you…”
  → [2. grant] app opens Custom Tab: join link + grant-files=1
      browser shows the dedicated GRANT page:
        checklist of the N files · one button → multi-select Picker
        per-file verification; missing files named + “Open the picker again”
        complete → “Return to EasyBC” (intent:// + https fallback)
  → [3. return] app receives sk-granted link (onNewIntent)
      JOIN screen auto-runs the join (warm auth): verify envelopes per rc.11,
      register profile, generate key response
  → [4. reply] RESPONSE-READY screen:
      “Send this reply to leslie@… to finish” · one share button (system sheet) + copy
      status: “Waiting for leslie@… to accept — this updates automatically.”
```

States (`JoinFlowUiState`, presentational screens implemented in
`ui/kit/JoinFlowScreens.kt`): `Preview(authenticating)` → `AwaitingGrant` →
`Joining` → `ResponseReady` → terminal; `Failed(message, canRetry)` from any
step keeps the pending link and re-enters at the failed step.

Edge handling:

- **User returns without completing the grant** (switched back manually):
  JOIN screen shows `AwaitingGrant` with "Reopen the browser" + "I already
  granted access — continue" (runs the join; a Drive 404 routes back to
  `AwaitingGrant` with the missing-file explanation rather than a raw error).
- **Process death during the browser trip**: pending link is durable
  (`PendingSharedJoin`); the `sk-granted` return re-opens the JOIN screen and
  the gate re-auths visibly before continuing.
- **Link opened twice / already joined**: joining is idempotent per
  `exchangeId`; a second open lands on `ResponseReady` with the stored
  response link (`PendingSharedJoin.producedResponse`).
- **Expired/invalid invitation**: `Failed` with "Ask the owner for a fresh
  invite link" — never a silent Toast.

## Owner journey (accept)

```
tap response link
  → app opens ACCEPT screen: “<email / key> accepted your invite” + grant summary
  → gate.run { auth }  “Confirming it's you…”
  → auto-processes: verify response · add keyGrants per granted dataset ·
      set Drive ACL · control.synchronizeMembers(...)
  → CONFIRMED screen: “Rachel now has access to Cycle & periods (view only)” ·
      [See people with access]
```

Failure keeps the response link pending with a Retry button and reports
partial failure precisely (encryption membership vs Drive ACL), as the
existing revocation flow already does.

## Web joiner (join link opened in a browser)

Same stages minus the app hop: JOIN preview screen (no more auto-running
`runJoinFromLink` on page load — auth must be behind the explicit Continue),
Picker inline, response link presented with share/copy. The web **grant page**
(`grant-files=1`) is only for the Android hand-off and never shows the app
shell.

## Wiring map (what's built vs to connect)

| Piece | Status |
| --- | --- |
| Web grant page (`ui/GrantAccessScreen.tsx`, routed from `main.tsx`) | **Built + tested** (checklist, partial-grant recovery, rc.11 ignore-unexpected-files, return intent link) |
| `InteractiveAuthGate` + auto-sync deferral + deep-link marking | **Built** (`sync/InteractiveAuthGate.kt`, `CloudAutoSyncSession`, `MainActivity`) |
| Join/accept presentational screens (`ui/kit/JoinFlowScreens.kt`) | **Built** — states + callbacks only |
| Nav route `join-flow` + view model driving the states | To wire: reuse `SettingsViewModel.authorizeAndJoinLink` / `authorizeAndAcceptLink` bodies, moved behind `InteractiveAuthGate.run`, split at the hand-off (auth ↦ browser ↦ join) |
| `sk-granted` in `MainActivity` → route to join-flow (replaces Toasts) | To wire (detection in place) |
| Web join screen replacing auto-run in `SyncSettings` | To wire |

## Copy rules

- Every wait names its actor: "Waiting for Google sign-in…", "Google Picker
  is open…", "Waiting for leslie@… to accept".
- Every failure says what to do next, in the flow, with a button.
- Auth prompts are always preceded by "Confirming it's you…" so a passkey
  sheet never appears unexplained.
