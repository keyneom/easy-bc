# EasyBC UI kit

The shared design system behind the profiles/settings redesign
([settings-profiles-redesign.md](settings-profiles-redesign.md)). Both
platforms implement the same tokens and components; build screens from these
instead of ad-hoc styles.

| Platform | Tokens | Components | Theme control |
| --- | --- | --- | --- |
| Web | [web/src/ui/tokens.css](../web/src/ui/tokens.css) | [web/src/ui/Kit.tsx](../web/src/ui/Kit.tsx) + [kit.css](../web/src/ui/kit.css) | [web/src/ui/theme.ts](../web/src/ui/theme.ts) |
| Android | [ui/theme/Theme.kt](../android/app/src/main/java/com/easybc/planner/ui/theme/Theme.kt) | [ui/kit/EbKit.kt](../android/app/src/main/java/com/easybc/planner/ui/kit/EbKit.kt) | `EasyBCTheme(themeMode)` |

**Living gallery (web):** run the app with `?uikit` in the URL. Note the
app's CSP blocks Vite's dev-mode inline styles, so styles only render from a
built bundle: `npm run build && npx vite preview`, then
`http://localhost:4173/easy-bc/?uikit`.

## Brand palette (sampled from the launcher logo)

| Token | Hex | Source |
| --- | --- | --- |
| `pink` | `#FFB6CC` | logo wordmark |
| `blush` | `#FFE0EC` | night-icon ground |
| `raspberry` | `#7B2D5F` | night-icon mark |
| `plum` | `#3E1E3C` | light-icon ground |

## Semantic tokens

| Token | Light | Dark |
| --- | --- | --- |
| accent / primary | raspberry `#7B2D5F` | pink `#FFB6CC` |
| accent container | blush `#FFE0EC` | `#5C2246` |
| on accent container | plum `#3E1E3C` | blush `#FFE0EC` |
| background | `#FBF7F9` | `#1B1319` |
| surface | `#FFFFFF` | `#241A21` |
| surface variant | `#F4EBF0` | `#2F2229` |
| ink / onSurface | `#241A21` | `#F2E7EC` |
| muted / onSurfaceVariant | `#6E5F68` | `#B5A3AC` |
| outline soft | `#EADFE5` | `#3C2E35` |
| ok | `#2E7D4F` | `#7CC79B` |
| warn | `#7A5410` | `#E3B34E` |
| danger / error | `#B3261E` | `#F2B8B5` |
| info | `#44618C` | `#94B2DC` |

**Storage modes** (semantic — never used as the accent): local stone
`#6E6E66`/`#A9A99F`, private teal `#256E62`/`#6FBFAF`, shared violet
`#5B4B8A`/`#A79BD1` (light/dark), each with a container tint.

**Datasets** (share grid, invite presets, dataset rows only — never on the
calendar): cycle `#7B2D5F`/`#E792B9`, plan `#44618C`/`#94B2DC`, intimacy
`#A8702D`/`#DBA96A`, sensitive `#5B5561`/`#ABA3B3`.

**Calendar indicators** carry over unchanged so logged meaning stays
recognizable: period `#C2185B` on `#FCE4EC`, fertile `#7B1FA2` on `#F3E5F5`,
actions U `#43A047` / W `#9E9D24` / C `#FB8C00` / A `#EF5350` with their
existing tinted backgrounds (translucent variants in dark), credits `#FFB300`,
risk low/med/high `#66BB6A`/`#FFA726`/`#EF5350`.

## Theme modes (system default · light · dark)

- **Web:** default follows `prefers-color-scheme`. An explicit choice
  (persisted in `localStorage["easybc.themeMode"]`) stamps
  `data-theme="light|dark"` on `<html>`; tokens.css makes that win over the
  media query in both directions. `initThemeMode()` runs at startup in
  main.tsx; `EbThemeModeToggle` is the user control (place it under Settings ▸
  About or Device). index.css legacy variables are remapped onto the tokens,
  and its literal dark-media rules are re-expressed through tokens in
  tokens.css's "theme enforcement" section — never add new literal
  `@media (prefers-color-scheme)` colors to index.css.
- **Android:** `EasyBCTheme(themeMode = ThemeMode.SYSTEM|LIGHT|DARK)`. Read
  `LocalEbDarkTheme.current` (not `isSystemInDarkTheme()`) wherever a
  composable branches on dark mode, so explicit overrides propagate; the
  persisted preference is app-side wiring (DataStore) passed into the theme
  at MainActivity.

## Components (web export ↔ Android composable)

| Web | Android | Purpose |
| --- | --- | --- |
| `EbAvatar` | `EbAvatar` | Initials/photo avatar + mode badge (local/private/shared/read-only/waiting) |
| `EbProfileChip` | `EbProfileChip` | Top-bar identity chip → opens the switcher |
| `EbGroupLabel` | `EbGroupLabel` | Uppercase section label |
| `EbNavRow` | `EbNavRow` | Hub row: icon · title · value summary · chevron (every hub row must render a value summary) |
| `EbProfileHeaderCard` | `EbProfileHeaderCard` | Active-profile header with Switch action |
| `EbModeCard` | `EbModeCard` | Storage-mode radio card; `pending` while the transition runs — only flip `selected` on confirmed success |
| `EbDatasetRow` | `EbDatasetRow` | Dataset with icon, scope line, share summary |
| `EbAccessSegmented` | `EbAccessSegmented` | None / View / Edit per dataset per person |
| `EbPersonCard` + `EbTrustBadge` | `EbPersonCard` + `EbTrustBadge` | Participant card; trust = "Account-verified" (control-dataset Google binding) or "Key from invite link" |
| `EbBanner` | `EbBanner` | info/warn/error/success contextual cards (legacy-migrate, waiting-for-owner, partial-failure retry) |
| `EbStatusRow` | `EbStatusRow` | ok/busy/warn/error status line (sync state) |
| `EbButton` | M3 buttons + `EbDangerTextButton` | primary / outline / danger-text |
| `EbPresetChip` | `EbPresetChip` | Invite sharing presets |
| `EbExpanderRow` | `EbExpanderRow` | Advanced / danger-zone expanders |
| `EbStepDots` | `EbStepDots` | Onboarding progress |
| `EbThemeModeToggle` | (wire with `ThemeMode`) | System / Light / Dark selector |

## Avatar color algorithm (cross-platform)

FNV-1a over the profile key's UTF-16 code units → hue; `hsl(hue, 48%, 44%)`;
initials = first grapheme of first + last words. Implemented identically in
`web/src/ui/avatarColor.ts` and `EbKit.avatarHue` — a profile must render the
same color on every platform. Photos: square center-crop, 128 px, WebP
(JPEG on iOS), quality ≈ 0.7, hard cap 12 KB; stored in the profile record
and synced via the `plan` dataset, never in the control dataset.

## Behavior rules the kit encodes

- **Today is always identifiable:** 2 px brand-primary ring + primary bold
  date number (selection uses the secondary hue so the two never blur), and
  **returning to the Calendar screen always re-centers on the current month**
  (web: `selectTab` in App.tsx; Android: `resetToCurrentMonth()` on screen
  entry). The Today button remains for mid-browse jumps.
- **Predicted vs confirmed:** any mutating control shows busy → confirmed/
  error explicitly (`pending` on EbModeCard, `EbStatusRow busy`, banner with
  Retry on partial failure). Never flip UI state before the operation lands.
- **Semantic color ≠ accent:** mode and dataset colors never appear on the
  calendar; the calendar's colors keep their logged meanings.
- **Web specificity defense:** index.css styles bare `<button>` as a filled
  primary pill with a hover fill; kit buttons re-assert their surface on
  hover (see "base-button defense" in kit.css). New kit buttons need the
  same treatment or they flash primary under the cursor.
