# Functional Requirements: Personal-Use Fertility Risk Planner

## Product Overview

A **personal-use contraceptive decision-support app** that helps a user plan sex across a cycle and across future years based on:

- current age
- target cumulative pregnancy-risk budget
- cycle length
- intercourse frequency
- barrier-method assumptions
- user preference for fewer abstinence days vs shorter abstinence streaks

This is **not** an FDA-cleared contraceptive product. It is a transparent planning tool for personal use.

For **client UX goals, information architecture, and a phased roadmap** (calendar-first shell, polish, and future credit→planner integration), see [`docs/ux-product-plan.md`](docs/ux-product-plan.md).

---

## Product Stance

### Current stance
- Personal use only
- On-device or local calculation preferred
- Transparent assumptions
- No clinical claims
- No ad-tech sharing of reproductive data
- No attempt to present this as an official contraceptive method

### Why this stance
The core value right now is:
- better planning
- clearer tradeoffs
- better visibility into riskier days
- dynamic optimization across a long horizon

rather than trying to make regulatory or marketing claims.

---

## Core Concept

Traditional fertility apps mainly say:
- fertile vs non-fertile
- red day vs green day

This planner instead says:

1. The user picks a **long-run cumulative risk target**
   - example: 1%, 2%, or 5% over 20 years

2. The app builds a **calendar-based biological risk model**
   - day-level fertile-window shape
   - ovulation timing uncertainty
   - age-adjusted fertility decline

3. The app optimizes the **entire horizon at once**
   - not just one cycle
   - not just one year
   - and not in a way that unfairly pushes all sexual freedom into later years

4. The app returns a **recommended action for each day**
   - `U` = unprotected
   - `W` = withdrawal (when enabled in the Rust core; see *Male-Side Method Extensions*)
   - `C` = condom
   - `A` = abstain

5. The app also shows **always-visible day weights**
   - raw risk score
   - approximate recovery cost if the user ignores that day's recommendation

---

## Key Design Principles

### 1. Whole-horizon optimization
The app must optimize across the full horizon because:
- fertility changes with age
- users value sexual freedom earlier, not just later
- a naive optimizer would otherwise push too much freedom into later years

### 2. Condoms first, abstinence second
The planner should:
1. start with all days unprotected
2. upgrade higher-risk days from `U -> C` (and, when withdrawal is enabled in the Rust core, from `U -> W` and `W -> C` per the marginal lattice in *Male-Side Method Extensions*)
3. only upgrade `C -> A` if condoms are still not enough to meet the target

This reflects the preference that abstinence is usually the highest-burden intervention.

**Known limitation of the sequential approach (reference JS snippet):** running all U→C upgrades before any C→A upgrades can be globally suboptimal. There may be cases where upgrading one marginal C→A frees room to leave multiple U→C on the table. Worth revisiting once baseline plans are hand-verified. For MVP, the sequential approach is acceptable because it's more interpretable and aligns with user preference ordering.

### 3. Single abstinence-pattern preference
The app should expose **one user-facing slider**:

**Streak aversion**
- `0` = prioritize the fewest total abstinence days
- `1` = accept more abstinence days in exchange for shorter abstinence streaks

This avoids making users tune multiple internal weights.

### 4. Always-visible day weights
Each day should always show:
- recommended action
- raw risk score
- approximate recovery cost if the user overrides that day

This gives users a sense of *how problematic* a day is, not just whether it is "allowed" or "forbidden."

### 5. Infeasibility must be surfaced explicitly
If the user's target is not achievable even with total abstinence during the fertile window (or even with total abstinence for all days), the app must say so clearly rather than silently producing a plan that fails to meet the target. See "Infeasibility Handling" section below.

---

## User Inputs

### Required
- Current age (integer years)
- Target **cumulative** pregnancy risk over the **entire** horizon (e.g. 5% over 20 years together — **not** a per-year failure rate)
- Horizon length in years
- Typical cycle length
- Intercourse frequency (acts per week)
- Condom mode
  - perfect
  - typical
  - custom residual-risk multiplier
- Streak aversion slider (0 to 1)

### Optional
- Custom condom residual value
- Cycle variability (ovulation-day standard deviation)
- Recently off hormonal contraception
- Personal notes / local-only metadata

**Note:** Cycle variability is currently declared as an input but the prototype uses a hardcoded ovulation SD of 3 days. This should be wired through to the ovulation distribution in a follow-up pass.

---

## Life-Cycle Evolution Modeling

The planner applies **age-dependent adjustments** to cycle length, ovulation variability, and intercourse frequency across the horizon. These are informed by published population data and scale the user's baseline values as they age.

### Cycle length evolution

Population data (Apple Women's Health Study 2023, n=165,668 cycles; TREMIN longitudinal data) shows cycle length follows a U-shaped curve with age:

- 18–19: ~30 days
- 20–24: ~29 days
- 25–29: ~28.5 days
- 30–34: ~28 days
- 35–39: ~27.5 days
- 40–42: ~27 days (minimum)
- 43–45: ~28 days (early perimenopausal lengthening begins)
- 46–47: ~32 days (transition accelerating)
- 48–49: ~40 days (late transition)
- 50+: ~55+ days (late perimenopausal)

The model uses these as reference values and **scales them relative to the user's reported baseline cycle length at their current age**. A user with a 26-day cycle at age 30 will have their future cycle lengths adjusted proportionally.

### Ovulation timing variability (SD) by age

Within-woman cycle variability follows a clear age pattern (Apple Women's Health Study):

- <20: ~5.0 days SD (46% higher than age 35–39)
- 20–24: ~4.0 days
- 25–29: ~3.5 days
- 30–34: ~3.2 days
- 35–39: ~3.0 days (minimum variability)
- 40–42: ~3.5 days
- 43–45: ~4.5 days
- 46–47: ~7.0 days (perimenopausal uncertainty rising)
- 48–49: ~10.0 days
- 50+: ~15.0 days

This drives the ovulation-day probability distribution used to build the per-day risk curve.

### Intercourse frequency decay

Population averages from NSFG and related studies show coital frequency declines with age. The model applies a **relative decay factor** from the user's reported baseline frequency, not an absolute replacement. A user reporting 4x/week at age 34 will see their future frequency scaled proportionally.

Reference population frequency (weekly):
- <25: ~4.5
- 25–29: ~4.0
- 30–34: ~3.5
- 35–39: ~3.0
- 40–44: ~2.5
- 45–49: ~2.0
- 50+: ~1.5

**Users can override these decay assumptions** via an advanced setting if their expected life trajectory differs.

### Calendar-method degradation warning

When per-year cycle SD exceeds 4.5 days (roughly age 45+ for population averages), the app must display a warning that calendar-based fertile-window prediction accuracy is materially reduced. Plans for these years should be treated as directional rather than precise, and users should be strongly encouraged to add body-signal tracking (Phase 3) before relying on the plan during those years.

### User override
All life-cycle values are reference population averages. Users can:
- Override baseline cycle length (affects starting reference)
- Override baseline frequency (affects starting reference)
- Disable age-based decay entirely (hold values constant)
- Provide their own age→cycle-length or age→frequency curves (advanced)

---

## Current Model Inputs and Assumptions

### Biological model
The current prototype uses:
- a fertile-window kernel centered around ovulation
- ovulation-day uncertainty for a regular cycle
- age-adjusted fertility decline

### Fertile-window kernel

The kernel represents per-act conception probability by day relative to ovulation (day 0). Current values approximate the Wilcox, Weinberg, and Baird (NEJM 1995) data:

- Day -5: 0.10
- Day -4: 0.16
- Day -3: 0.14
- Day -2: 0.27
- Day -1: 0.31
- Day 0: 0.33
- Day +1: 0.08 (corrected — Wilcox shows roughly 0.08–0.10, not zero)
- Day +2 and later: 0.00

**Data sources to cite in documentation:**
- Wilcox, Weinberg, Baird (1995) "Timing of Sexual Intercourse in Relation to Ovulation" NEJM 333:1517
- Wilcox et al. (2000) "The Timing of the 'Fertile Window' in the Menstrual Cycle" BMJ 321:1259
- Dunson, Colombo, Baird (2002) "Changes with age in the level and duration of fertility in the menstrual cycle" Human Reproduction 17:1399

### Age adjustment
Current age multipliers (approximations of per-cycle fecundability ratios from Dunson 2002 and Steiner 2011):

- 19–26: `1.00`
- 27–29: `0.86`
- 30–34: `0.77`
- 35–37: `0.63`
- 38–40: `0.49`
- 41–44: `0.28`
- 45+: `0.10`

**Known limitation:** these are approximate bracket values. Published fecundability curves are continuous; the bracket approach creates small discontinuities at bracket boundaries. Users crossing a boundary (e.g., turning 30 or 35) will see a step-change in their plan. Consider smoothing to a continuous function in Phase 2.

### Calibration philosophy

The model uses Wilcox per-act conception probabilities **directly** as absolute numbers, without global scaling. This is the honest approach — these values represent clinically recognized pregnancy per act from biological data, and they should stand on their own.

The **only** parameters that need calibration are the per-act condom residuals (how much a condom reduces conception probability per act). These are back-solved at a fixed reference population:

- Reference: age 28, 28-day cycle, SD=2.0 days, 2 acts/week
- Back-solved perfect-use residual: ~0.0045 per act (produces 2% annual failure at reference)
- Back-solved typical-use residual: ~0.031 per act (produces 13% annual failure at reference)

These residuals are then used uniformly for all users. Users with higher frequencies than the reference population will see proportionally higher annual failure rates — this is biologically correct (more acts = more opportunities for method failure) and is what makes the planner responsive to frequency.

### What the SDM anchor tells us (and doesn't)

Running the model on a reference SDM scenario (age 28, 28-day cycle, SD=1.5, 2 acts/week, abstain days 8–19) produces approximately 11% annual failure — roughly 2x higher than SDM's published 5% perfect-use rate.

This deviation is expected and acceptable:
- Wilcox's per-act conception probabilities were measured in couples actively trying to conceive, who may have higher fecundity than average
- SDM study populations were self-selected (only 26–32 day cycles) and possibly had better adherence than the general population
- The model's conservative tendency is a safety property, not a bug

**Users should expect the model to predict higher annual failure rates than familiar contraceptive benchmarks.** This reflects honest biological uncertainty rather than calibration error. If a user's target is 1% over 20 years, the model may suggest more restrictive plans than subscription FAM apps because it isn't shaving numbers to match marketing-friendly effectiveness claims.

**Calibration limitations to document for users:**
- Condom residuals are calibrated at reference population (freq=2/week); users with higher frequency will see proportionally higher annual risks
- Wilcox biological data may overestimate conception risk for general populations vs TTC populations
- Published contraceptive effectiveness rates (2%, 5%, 13%) depend on the frequency distribution of the study populations and cannot be directly compared to a personalized per-act model
- The model's estimates should be interpreted as "given my specific frequency, age, and cycle, here is the biology-derived risk" — not "this should match published rates"
- **Double-check logic:** the current implementation spreads `actsPerWeek` into an average daily rate (`actsPerWeek / 7`) and applies day-level risk once per day. This may not fully capture days with multiple intercourse instances, and should be verified against study assumptions that report per-day conception likelihood rather than per-instance likelihood.

---

## Current Optimization Logic

### Objective
Minimize behavioral burden while meeting the selected cumulative risk target.

### Burden ordering
The optimizer currently prefers:
1. fewer abstinence days
2. more condoms instead of abstinence when possible
3. shorter abstinence streaks when the user's streak aversion is higher
4. smoother plans across years
5. avoiding the degenerate solution of "all the freedom later"

### Current action set
- `U` = unprotected
- `C` = condom
- `A` = abstain

### Current optimization phases
1. initialize all days as `U`
2. upgrade positive-risk days `U -> C` where that produces the best risk reduction per burden cost
3. if needed, upgrade `C -> A`
4. stop once the horizon-wide cumulative risk is at or below the target

### Time preference and year crowding
Two regularizers prevent degenerate solutions:

- `timePreferenceRate` (default 0.03): discounts the burden cost of restrictions in later years. Without this, the optimizer would push all restrictions into later years when fertility is lower. The 3% rate is arbitrary and approximates typical economic discounting; it should be user-configurable in a later version.
- `yearCrowdingPenalty` (default 0.8): increases burden cost for a year as its burden accumulates. Prevents the optimizer from concentrating all restrictions in any single year.

**These two regularizers together shape how restrictions distribute across the horizon.** They should be documented for users who want to understand plan shape, and potentially exposed as advanced controls.

---

## Infeasibility Handling

Before displaying a plan, the app must check whether the target is achievable:

1. **Compute minimum achievable cumulative risk** by setting all fertile-window days to `A` and all other days to `C` (or all days to `A` if that's still insufficient).
2. If minimum achievable > target, the plan is infeasible. Display clearly:
   - What the achievable minimum is
   - What inputs would need to change to make the target feasible (longer horizon, higher target, different age bracket, lower frequency)
   - Suggested alternatives (e.g., copper IUD if truly targeting <0.5% over 20 years)
3. If target is achievable but requires >50% abstinence days, warn the user before producing the plan.

---

## Outputs

For each year in the horizon, display:

- age
- expected cycle risk
- expected annual risk
- day plan
- count of:
  - unprotected days
  - condom days
  - abstinence days

### Day-level display
For each day:
- `recommendedAction`
- `rawRiskScore` (0–100 within that year's cycle)
- `overrideCost`

### Override cost
Current interpretation:
- if the day is `A`, the likely override is sex with a condom
- if the day is `C`, the likely override is unprotected sex
- if the day is `U`, no override cost is shown by default

The current implementation estimates recovery cost as:
- approximate number of additional condom days
- approximate number of additional abstinence days

needed elsewhere in the same cycle to offset the added risk.

This is an approximation for UX, not a clinical guarantee.

**Known gap:** when same-cycle recovery is not available (e.g., override happens on peak ovulation day late in cycle), the current implementation returns a note that broader replanning is needed, but doesn't actually perform that replanning. This is intentional for MVP — users are just told "this needs bigger replanning" — but should be wired into the incident-replay flow in Phase 2.

### User-chosen calendar edits and preview replan (implemented in core + web)

Users should be able to **lock specific days** to the action they intend (e.g. switch `A → C` or `A → U`, or tighten `U → C`) and see how the optimizer **compensates on other days** while trying to stay under the **cumulative horizon** risk target.

- **Rust / WASM:** `replan_preview(options, overrides)` and JSON `replanPreviewJson` accept a list of [`DayOverride`](crates/planner-core/src/types.rs) entries (`year_index`, 1-based `day`, `action`). The preview plan keeps locked days fixed and runs the usual greedy upgrades on **unlocked** days until the target is met again or reports `feasible: false` with an explanatory message.
- **Web:** [`web/src/App.tsx`](web/src/App.tsx) supports **multiple locked days** (add overrides, preview once, apply preview to baseline or clear locks).
- **Web (calendar mode):** optional **past as-lived** behavior: per-cell **day logs** (`session.dayLogs`) are merged with user locks into `initial_action_locks` for all grid cells strictly before “today” via [`initialLocksForPastDays`](web/src/sessionUtils.ts) (default as-lived action `U` when a cell has no log).
- **Not yet:** automatic “burn budget then replan” after an incident (see *Dynamic Replanning After Violations* below); **voluntary-abstinence credits** are a local journal in the web UI and do not yet change numeric optimization.

---

## Period tracking, calendar UX, and predicted cycles

### Default in the Rust core

Unless the user supplies an explicit **calendar cycle list** (`calendar_cycles` / calendar-mode `UserOptions`), each horizon index uses **population priors** by age: expected cycle length, ovulation SD, and frequency scale. That remains the generic default for “representative year *i*.”

### Web tracker + shell (implemented)

The **web client** adds a **calendar-first** layer on top of WASM:

1. **Period records** — Start and end (or open) bleeding episodes persisted in **IndexedDB** (`periodRecords`; legacy `periodStarts` is migrated). The **History** tab lists episodes and derived lengths.
2. **Predicted / sample cycles for the planner** — From logged starts, the app builds a sequence of cycle lengths used when **calendar mode** is on so the optimizer grid aligns with **wall-calendar** rows instead of abstract “year 0, year 1” only.
3. **Month calendar** — Real **Gregorian** month grid (Monday-first): shades **bleeding** days; shows a **fertile-window estimate** from calendar math (explicitly a **guess**, not body-signal data); when a wall-calendar plan exists, each day can show the optimizer **action** (`U` / `W` / `C` / `A`) and **raw risk score** (0–100 within that cycle in the model), with **Compact** vs **Comfortable** density (risk in tooltip when compact).
4. **Day detail** — Set period start / last bleeding day, see **phase estimate** and cycle-day guess, **optimizer recommendation + raw risk + override-cost summary** when calendar mode has a plan, toggle **voluntary-abstinence credit** (journal only for now; see [`docs/ux-product-plan.md`](docs/ux-product-plan.md)).
5. **Variance → ovulation SD** — From **≥2** inferred cycle lengths, the web app computes sample spread and adds a capped **widen** term to the baseline ovulation SD on each predicted row (`sdWidenFromVariance` in [`web/src/periodTracker.ts`](web/src/periodTracker.ts)), so noisier lengths widen the **modeled** fertile window in the core and tend to produce more **condom/abstinence** days for the same cumulative target. The **Planner** tab summarizes counts, sample SD, and effective row SD when calendar mode is on.
6. **iCal export (calendar-mode only)** — Download an **.ics** of all mapped wall dates in the current plan: each day is an all-day event with **SUMMARY** `easy-bc: <action> · risk <n>` and a **DESCRIPTION** disclaimer plus override-cost note (see [`web/src/export/plannerToIcs.ts`](web/src/export/plannerToIcs.ts)). Requires the same preconditions as the wall overlay (period anchor + `calendarCycles` + computed `plan`). No export for abstract non-calendar horizon rows in v1.
7. **Session fields** — Persists `locks`, `realizedCumulativeRisk`, `dayLogs`, `ecJournalFlag`, `voluntaryAbstinenceDates`, and related planner session state (see [`web/src/sessionUtils.ts`](web/src/sessionUtils.ts)).

**Code map:** [`web/src/tracker/`](web/src/tracker/) (types, calendar math, [`plannerWallMeta.ts`](web/src/tracker/plannerWallMeta.ts) wall-date ↔ planner slice), [`web/src/components/MonthCalendar.tsx`](web/src/components/MonthCalendar.tsx), [`web/src/components/DayDetailPanel.tsx`](web/src/components/DayDetailPanel.tsx), [`web/src/export/plannerToIcs.ts`](web/src/export/plannerToIcs.ts), [`web/src/idbStore.ts`](web/src/idbStore.ts), [`web/src/App.tsx`](web/src/App.tsx).

### Still to build (product / research)

- A fuller **individual posterior** on cycle length and variability from many observations (beyond current prediction heuristics).
- **Rolling horizon** UX that always highlights plan diffs when period data changes.
- **Credits → planner policy** (how journal entries affect optimization).
- **Incident replay** wired end-to-end (see *Dynamic Replanning After Violations*).
- **Current-cycle wall-calendar continuity audit** — verify/fix cases where after logging a new period start, immediately following days in the current cycle lose phase/recommendation coloring until roughly one cycle later. The most important surface is the active cycle.
- **Optional per-day body signals** — mucus / Mittelschmerz / breast-tenderness / manual BBT / manual OPK, logged opt-in, never prompted for. Used to shift and narrow the predicted fertile window for users who want it (zero effort for users who don't).
- **Anovulatory cycle flag** — auto-detect atypical cycles (length or bleeding-duration outlier vs user's own posterior, floored to avoid over-flagging very-regular users; backstopped by population hard bounds 21–40 days / 2–8 bleed days). Widens the fertile window for that cycle and shows a small 'atypical' chip. Zero user effort.
- **Reconciliation chip + cycle risk ledger** — when yesterday's planned C/U day is unreconciled, show a single-tap chip (As planned / Abstained / Breakage / No activity) on the home screen. Day-level reconciliation over arbitrary ranges supported for batch entry. Realized-risk budget is tracked against target, so abstained-on-a-planned-C days earn credit the user can spend on a U day later without exceeding the cycle's cumulative risk target.
- **Opt-in daily nudge** — off by default, a user-configurable single-time-per-day notification that just deep-links to the reconciliation chip (no interactive buttons in v1).

### Recently added (Android)

- **Period-end prediction for open periods** — `effectiveBleedingEndEpochDay` (Android) and `derivedBleedingEnd` (web) no longer cap bleeding-end at today for an unresolved period. They predict forward to `startDate + personalMeanBleedDays - 1` (falling back to 5 days with <3 closed samples), capped only at the day before the next logged period start. Fertile-window and risk math are unaffected — cycle length is measured start-to-start, so only the painted "P" range changes.
- **Native device calendar sync (`EasyBCCalendarSync`)** — creates a local-only "EasyBC Planner" calendar via `CalendarContract` with `ACCOUNT_TYPE_LOCAL` (never leaves the device unless the user explicitly moves events). One composite all-day event per day joining period / fertile / action components with ` + ` (e.g. `P + C`, `P + A`). Auto-sync via `CalendarAutoSync` watches all four data flows and debounces writes at 1.5s. Event titles default to cryptic single letters (P/F/U/C/A/W), user-editable in Settings for bystander privacy.
- **JSON backup/restore (`DataBackup`)** — versioned export of periods + day logs + settings via SAF, one-tap re-import on another device. No account needed.
- **Explicit Room migrations** — `MIGRATION_1_2`, `MIGRATION_2_3`, `MIGRATION_3_4` replace the previous `fallbackToDestructiveMigration()` footgun. User data is preserved across app updates; Auto Backup (`android:allowBackup="true"`) covers reinstall and device-swap.

---

## Dynamic Replanning After Violations

### Planned next-step behavior
If a user reports:
- sex on an abstinence day
- condom use on an abstinence day
- condom failure
- unprotected sex on a condom day

the app should:
1. treat that event as a real exposure
2. consume part of the remaining risk budget
3. lock past events
4. re-optimize the rest of the current cycle and future horizon

### Current status in Rust
- **`realized_cumulative_risk`** on [`UserOptions`](crates/planner-core/src/types.rs): lowers the effective cumulative budget before optimization (`target − realized`, floored at zero). Use when the user has already logged exposures against the horizon budget.
- **`initial_action_locks`**: list of [`DayOverride`](crates/planner-core/src/types.rs) applied before the greedy optimizer runs; those cells stay fixed (same skip semantics as replan locks).
- **Incident → fields mapping** (v1 placeholders, not clinical): see [`docs/incidents.md`](docs/incidents.md).

---

## Always-Visible Day Weights

### Included now
- raw risk score
- override cost

### Not currently shown in the core snippet
- explicit budget-burn percentages
- emergency-contraception routing
- exact post-incident replanning inside the same function

---

## Male-Side Method Extensions

### Current decision
The only male-side behavior likely worth modeling as a real method layer is:
- **withdrawal**

### Current status (core)
- `W` (**withdrawal**) is a first-class [`RecommendedAction`](crates/planner-core/src/types.rs) with per-day risk multiplier **`withdrawal_relative_risk`** on [`UserOptions`](crates/planner-core/src/types.rs) (default `0.35`, serde **`withdrawalRelativeRisk`** in JSON).
- Greedy marginal upgrades follow this lattice (cheaper steps use fractional condom-UX cost): **`U→W`**, **`U→C`**, **`W→C`**, **`W→A`**, **`C→A`**, picking the best benefit/cost each iteration until the effective cumulative target is met.

### Not modeled yet
- combined method layers such as **`CW`** (condom + withdrawal) as their own action

**Caveat:** withdrawal’s published effectiveness (typical-use failure rates) reflects significant user-correlation with other method failures. Modeling it as an independent layer is optimistic. Consider applying a correlation discount when combined with condoms.

---

## Testing and Validation

Before shipping, the planner must pass the following hand-verified test cases:

### Correctness tests
1. **All-abstinence baseline:** with target 0%, verify the plan marks all positive-risk days as `A`.
2. **All-unprotected baseline:** with target 99%, verify the plan marks all days as `U`.
3. **Monotonic in age:** verify that older starting ages produce fewer abstinence/condom days for the same target (fertility declines).
4. **Monotonic in target:** verify that higher risk targets produce fewer abstinence/condom days.
5. **Monotonic in frequency:** verify that higher acts/week produces more abstinence/condom days.
6. **Monotonic in horizon:** verify that longer horizons at the same annual-equivalent target produce similar per-cycle plans.

### Sanity tests
1. Plan at age 34 + 20-year horizon + 1% target should produce substantially fewer abstinence days than plan at age 20 + 20-year horizon + 1% target.
2. Plan with perfect condoms should have fewer abstinence days than plan with typical condoms, same inputs otherwise.
3. Streak aversion = 0 should produce plans with contiguous abstinence blocks; streak aversion = 1 should produce plans with interleaved abstinence days.

### Output plausibility
Spot-check 3–5 plan outputs against the research data — e.g., peak-fertility days (cycle days 11–15 for a 28-day cycle) should always be `A` or `C`, never `U`, for any non-trivial target.

---

## Current Scope

### In scope now
- calendar-only planning
- age-adjusted fertility decline
- **age-based lifecycle evolution** of representative-cycle length, ovulation SD, and acts/week across the horizon (from population reference curves), unless `holdLifecycleConstant` is true
- whole-horizon optimization
- condoms-first logic
- abstinence streak preference via one slider
- day-level raw risk score
- day-level recovery-cost estimate
- preview replan with user **locks** (`replan_preview` / `DayOverride`); see below
- optional **explicit calendar horizon**: a list of per-cycle specs (length, SD, frequency, age) when using `calendar_cycles` / `UserOptions` calendar mode (for period-informed planning)
- **withdrawal (`W`)** and the **`U→W→C→A` upgrade lattice** with `withdrawal_relative_risk` (see *Male-Side Method Extensions*)
- local transparent code
- infeasibility detection and warning (condoms-alone vs target; heavy abstinence)

### Out of scope in current core function
- emergency contraception **PK** workflow (efficacy math); UX education / local journal only in the web shell
- body-signal integration (BBT, LH strips, etc.)
- machine learning
- FDA / research / provider workflows
- **incident replay** with automatic budget burn (planned; hooks documented—see *Dynamic Replanning*)
- **full Bayesian / clinical-grade fertility inference** from body signals or a complete individualized posterior (the web shell logs periods and supplies predicted cycle rows to calendar mode; the core remains the single numeric engine)

---

## Non-Functional Goals

### Privacy
- local-first preferred
- no reproductive-data sharing by default
- plain export / delete capability (web: **calendar-mode** planner projections can be downloaded as a local **.ics** file; treat exports like any other sensitive file)
- no ad-network use of reproductive data

### Transparency
- user can inspect assumptions
- code can be audited
- output should be explainable

### Performance
- **Shipped core:** Rust (`planner-core`) should run instantly for normal horizons on device or via WASM.
- The embedded **JavaScript snippet** below remains a **reference implementation** for readability and regression parity (see `scripts/reference-planner.mjs`), not a second production planner.

---

## Recommended implementation stack

**Canonical implementation:** **Rust** crate [`crates/planner-core/`](crates/planner-core/).

- **Web:** compile to **WebAssembly** (`wasm32-unknown-unknown`, `wasm-bindgen`); thin **Vite + React** client in [`web/`](web/) calls JSON APIs (`planFertilityRiskJson`, `replanPreviewJson`).
- **Mobile (Android / iOS):** same crate via **uniFFI** (optional `ffi` feature)—Kotlin / Swift bindings; native UI (Jetpack Compose / SwiftUI) or future React Native with thin native modules.
- **Why one Rust core:** one audited numeric path for all platforms; JS/TS is not duplicated for production planning logic.

### Web application — features and intent

The web client is a **local-first shell**: **IndexedDB** for period records, **session storage** for planner session, **tabs** (**Calendar** | **Planner** | **History**), and WASM for all numeric plans.

- **Calendar** — Month grid with bleeding and fertile **estimates**; with calendar mode + a computed plan, per-day **optimizer action** and **raw risk score** (density toggle); **Export planner (.ics)** for the mapped date range; **day panel** for period boundaries, phase copy, **optimizer fields**, and voluntary-abstinence **credits** (journal).
- **Planner** — Full `UserOptions` surface, **cycle-length variance card** (sample SD, SD widen, effective `cycleSdDays` on predicted rows), horizon strip with locks and as-lived logs, **preview replan**, realized cumulative risk, EC education / journal flag (no PK workflow in core).
- **History** — Table of logged period episodes.

UX roadmap and interaction principles: [`docs/ux-product-plan.md`](docs/ux-product-plan.md). Incident → risk fields (v1): [`docs/incidents.md`](docs/incidents.md).

---

## Assumptions and Disclaimers

- Assumes regular cycles (SD ≤ 3 days). Users with more variable cycles need body-signal tracking.
- Calendar-based only.
- Not medical advice.
- Not FDA-cleared as contraception.
- Strongly affected by user adherence.
- Violations can materially change long-run risk.
- **Default horizon model:** each horizon index is a **representative menstrual cycle** at `age_years + index` (calendar years of aging), with length, ovulation SD, and frequency **scaled by age** from reference curves unless `holdLifecycleConstant` is true. That is **not** the same as a sequence of **your** logged cycle lengths unless you pass an explicit **calendar cycle list** (see [Period tracking, calendar UX, and predicted cycles](#period-tracking-calendar-ux-and-predicted-cycles) and `UserOptions` / `CycleInstance` in Rust).
- Better as a planning and transparency tool than a claim-making product.

---

## Current Core Algorithm Snippet

The snippet below is the **current source of truth** for the planner logic.
It includes:
- whole-horizon optimization
- condoms-first then abstinence
- age-adjusted risk
- single streak-aversion control
- raw risk score
- override-cost estimate

**Outstanding changes to apply to the snippet:**
1. ~~Fix `fertileKernel[1]` from `0.00` to `0.08`~~ ✓ Applied
2. ~~Wire `ovulationSdDays` through from user input~~ ✓ Replaced with age-dependent function
3. ~~Add infeasibility check before optimization loops~~ ✓ Applied
4. ~~Add perimenopausal warning when any horizon year has age ≥ 42~~ ✓ Applied (now driven by cycle SD threshold)
5. ~~Add life-cycle evolution modeling~~ ✓ Applied (cycle length, variability, frequency all age-adjusted)

```javascript
function fertilityRiskPlanner(userOptions = {}) {
  /*
    Personal-use contraceptive decision-support prototype.

    What this function does:
    1) Builds a calendar-only biological day-risk curve for each year of the horizon.
    2) Applies age-adjusted fertility decline year by year.
    3) Calibrates the internal scale so it is not wildly harsher than SDM/condom
       benchmark assumptions.
    4) Optimizes across the FULL horizon (not year-by-year):
         - start all days unprotected
         - upgrade positive-risk days from Unprotected -> Condom first
         - only if still needed, upgrade Condom -> Abstain
       while penalizing long abstinence streaks and dumping all restriction into the
       earliest years.
    5) Returns a year-by-year calendar plus always-visible day weights:
         - rawRiskScore: 0..100 within that year's cycle
         - overrideCost: approximate recovery cost if the user ignores the recommendation

    What this function does NOT yet do:
    - body-signal integration
    - dynamic incident replay / emergency contraception flow
    - withdrawal (W) or condom+withdrawal combination methods — these are implemented in the
      canonical Rust core / WASM, not in this JavaScript reference
    - exact clinical effectiveness estimation
  */

  const opts = {
    ageYears: 34,
    horizonYears: 20,
    targetCumulativeFailure: 0.05, // cumulative over full horizon, not per year
    cycleLengthDays: 28,
    actsPerWeek: 3.5,
    condomMode: "typical", // "perfect" | "typical" | "custom"
    customCondomResidual: 0.08,

    // Single user-facing preference knob:
    // 0 => fewest abstinence days
    // 1 => willing to accept more abstinence days to break up long streaks
    streakAversion: 0.50,

    // Hold life-cycle values constant across horizon (disables age-based evolution)
    holdLifecycleConstant: false,

    // Calibration anchors / internal model assumptions
    cyclesPerYear: 13,
    sdmReferenceAnnualFailure: 0.05,
    condomPerfectAnnualFailure: 0.02,
    condomTypicalAnnualFailure: 0.13,
    ovulationSdDays: 3,
    ovulationWindowHalfWidth: 7,

    // Optimization regularizers
    yearCrowdingPenalty: 0.8,
    timePreferenceRate: 0.03,
    improvementFloor: 1e-14,

    ...userOptions,
  };

  if (opts.ageYears < 15 || opts.ageYears > 55) throw new Error("ageYears must be between 15 and 55");
  if (opts.horizonYears < 1 || opts.horizonYears > 40) throw new Error("horizonYears must be between 1 and 40");
  if (opts.cycleLengthDays < 21 || opts.cycleLengthDays > 45) throw new Error("cycleLengthDays must be between 21 and 45");
  if (!(opts.targetCumulativeFailure > 0 && opts.targetCumulativeFailure <= 0.5)) throw new Error("targetCumulativeFailure must be > 0 and <= 0.5");
  if (!["perfect", "typical", "custom"].includes(opts.condomMode)) throw new Error("condomMode must be perfect, typical, or custom");
  if (!(opts.customCondomResidual >= 0 && opts.customCondomResidual <= 1)) throw new Error("customCondomResidual must be between 0 and 1");
  if (!(opts.streakAversion >= 0 && opts.streakAversion <= 1)) throw new Error("streakAversion must be between 0 and 1");

  const s = opts.streakAversion;
  const UX = {
    condomCost: 1.0,
    abstainCost: 8.0 - 3.0 * s,
    streakPenalty: 2.0 + 10.0 * s,
    softMaxAbstainStreak: s < 0.33 ? 4 : s < 0.66 ? 3 : 2,
    softMaxStreakPenalty: 2.0 + 20.0 * s,
  };

  // Per-act conception probability by day relative to ovulation
  // Source: Wilcox, Weinberg, Baird (NEJM 1995) and Wilcox et al (BMJ 2000)
  const fertileKernel = {
    [-5]: 0.10,
    [-4]: 0.16,
    [-3]: 0.14,
    [-2]: 0.27,
    [-1]: 0.31,
    [0]: 0.33,
    [1]: 0.08,  // Corrected from 0.00 — Wilcox shows nonzero post-ovulation
  };

  function ageMultiplier(age) {
    if (age >= 19 && age <= 26) return 1.00;
    if (age >= 27 && age <= 29) return 0.86;
    if (age >= 30 && age <= 34) return 0.77;
    if (age >= 35 && age <= 37) return 0.63;
    if (age >= 38 && age <= 40) return 0.49;
    if (age >= 41 && age <= 44) return 0.28;
    if (age >= 45) return 0.10;
    return 1.00;
  }

  // Reference population cycle length by age (days)
  // Source: Apple Women's Health Study 2023 (n=165,668 cycles), TREMIN longitudinal data
  function referenceCycleLengthForAge(age) {
    if (age < 20) return 30.0;
    if (age < 25) return 29.0;
    if (age < 30) return 28.5;
    if (age < 35) return 28.0;
    if (age < 40) return 27.5;
    if (age < 43) return 27.0;
    if (age < 46) return 28.0;
    if (age < 48) return 32.0;
    if (age < 50) return 40.0;
    return 55.0;
  }

  // Reference within-woman cycle SD by age (days)
  // Source: Apple Women's Health Study 2023
  function referenceCycleSdForAge(age) {
    if (age < 20) return 5.0;
    if (age < 25) return 4.0;
    if (age < 30) return 3.5;
    if (age < 35) return 3.2;
    if (age < 40) return 3.0;
    if (age < 43) return 3.5;
    if (age < 46) return 4.5;
    if (age < 48) return 7.0;
    if (age < 50) return 10.0;
    return 15.0;
  }

  // Reference population coital frequency by age (acts/week)
  // Source: NSFG and related population data (approximations)
  function referenceFrequencyForAge(age) {
    if (age < 25) return 4.5;
    if (age < 30) return 4.0;
    if (age < 35) return 3.5;
    if (age < 40) return 3.0;
    if (age < 45) return 2.5;
    if (age < 50) return 2.0;
    return 1.5;
  }

  // Scale user's baseline to other ages using relative population curves.
  // If user provided baseline at their current age, future values are adjusted
  // by the ratio of reference curves.
  function scaledCycleLengthForAge(age) {
    if (opts.holdLifecycleConstant) return opts.cycleLengthDays;
    const refBaseline = referenceCycleLengthForAge(opts.ageYears);
    const refAtAge = referenceCycleLengthForAge(age);
    const scaled = opts.cycleLengthDays * (refAtAge / refBaseline);
    // Round to integer days, clamp to plausible range
    return Math.max(21, Math.min(60, Math.round(scaled)));
  }

  function scaledCycleSdForAge(age) {
    if (opts.holdLifecycleConstant) return opts.ovulationSdDays;
    // Cycle SD is primarily driven by age, not personal baseline.
    // But if user reported a personal SD, we preserve the relative shape.
    const refBaseline = referenceCycleSdForAge(opts.ageYears);
    const refAtAge = referenceCycleSdForAge(age);
    return opts.ovulationSdDays * (refAtAge / refBaseline);
  }

  function scaledFrequencyForAge(age) {
    if (opts.holdLifecycleConstant) return opts.actsPerWeek;
    const refBaseline = referenceFrequencyForAge(opts.ageYears);
    const refAtAge = referenceFrequencyForAge(age);
    return opts.actsPerWeek * (refAtAge / refBaseline);
  }

  function gaussianWeight(x, mean, sd) {
    const z = (x - mean) / sd;
    return Math.exp(-0.5 * z * z);
  }

  function sum(arr) {
    let total = 0;
    for (let i = 0; i < arr.length; i++) total += arr[i];
    return total;
  }

  function cycleFromAnnualRisk(annualRisk) {
    return 1 - Math.pow(1 - annualRisk, 1 / opts.cyclesPerYear);
  }

  function annualFromCycleRisk(cycleRisk) {
    return 1 - Math.pow(1 - cycleRisk, opts.cyclesPerYear);
  }

  function ovulationWeights(cycleLengthDays, sdDays) {
    const center = cycleLengthDays - 14;
    const minDay = Math.max(1, center - opts.ovulationWindowHalfWidth);
    const maxDay = Math.min(cycleLengthDays, center + opts.ovulationWindowHalfWidth);
    const raw = [];
    for (let day = minDay; day <= maxDay; day++) {
      raw.push([day, gaussianWeight(day, center, sdDays)]);
    }
    const total = raw.reduce((acc, [, w]) => acc + w, 0);
    return raw.map(([day, w]) => [day, w / total]);
  }

  function rawPerDayCycleRiskForAge(age, cycleLengthDays, actsPerWeek, sdDays) {
    const actsPerDay = actsPerWeek / 7;
    const ovu = ovulationWeights(cycleLengthDays, sdDays);
    const ageMult = ageMultiplier(age);
    const out = [];

    for (let cycleDay = 1; cycleDay <= cycleLengthDays; cycleDay++) {
      let p = 0;
      for (const [ovulationDay, weight] of ovu) {
        const rel = cycleDay - ovulationDay;
        p += weight * (fertileKernel[rel] || 0);
      }
      out.push(p * ageMult * actsPerDay);
    }
    return out;
  }

  // Back-solve condom per-act residuals at a FIXED reference population so
  // the model matches published annual anchors at that population. These
  // residuals then apply uniformly to all users; if a user has higher frequency
  // than the reference, their annual risk will scale up correctly.
  //
  // Reference population: age 28, 28-day cycle, SD=2.0, freq=2/wk (approximating
  // typical contraceptive effectiveness study populations).
  function referenceDayRisks() {
    return rawPerDayCycleRiskForAge(28, 28, 2.0, 2.0);
  }

  function annualRiskWithResidual(dayRisks, residual) {
    let cycleSurv = 1;
    for (const r of dayRisks) cycleSurv *= (1 - r * residual);
    return 1 - Math.pow(cycleSurv, opts.cyclesPerYear);
  }

  function solveResidualForAnnualTarget(targetAnnual, dayRisks) {
    let lo = 0, hi = 1;
    for (let i = 0; i < 50; i++) {
      const mid = (lo + hi) / 2;
      if (annualRiskWithResidual(dayRisks, mid) < targetAnnual) lo = mid;
      else hi = mid;
    }
    return (lo + hi) / 2;
  }

  const refDayRisks = referenceDayRisks();
  const condomResiduals = {
    perfect: solveResidualForAnnualTarget(opts.condomPerfectAnnualFailure, refDayRisks),
    typical: solveResidualForAnnualTarget(opts.condomTypicalAnnualFailure, refDayRisks),
    custom: opts.customCondomResidual,
  };

  const condomResidual = condomResiduals[opts.condomMode];

  function multiplierForAction(action) {
    if (action === "U") return 1;
    if (action === "C") return condomResidual;
    return 0;
  }

  // Build years with age-adjusted cycle length, variability, and frequency
  const years = [];
  for (let y = 0; y < opts.horizonYears; y++) {
    const age = opts.ageYears + y;
    const cycleLen = scaledCycleLengthForAge(age);
    const cycleSd = scaledCycleSdForAge(age);
    const freq = scaledFrequencyForAge(age);
    const raw = rawPerDayCycleRiskForAge(age, cycleLen, freq, cycleSd);
    years.push({
      yearIndex: y,
      age,
      cycleLengthDays: cycleLen,
      cycleSdDays: cycleSd,
      actsPerWeek: freq,
      baseRiskByDay: raw,
    });
  }

  // Validation: simulate SDM-like policy at SDM's reference population
  // (Arevalo 2002: 26-32 day cycles, typical freq ~2/wk) and check model
  // produces ~5% annual failure. Differences from anchor reflect study
  // population differences and inherent uncertainty in Wilcox data.
  function validateAgainstSdm() {
    const sdmRefRisk = rawPerDayCycleRiskForAge(28, 28, 1.5, 2.0);
    let cycleSurv = 1;
    for (let i = 0; i < sdmRefRisk.length; i++) {
      const day = i + 1;
      const protectedBySDM = day >= 8 && day <= 19;
      if (!protectedBySDM) cycleSurv *= (1 - sdmRefRisk[i]);
    }
    const cycleRisk = 1 - cycleSurv;
    const annualRisk = 1 - Math.pow(1 - cycleRisk, opts.cyclesPerYear);
    const anchor = opts.sdmReferenceAnnualFailure;
    const deviation = Math.abs(annualRisk - anchor) / anchor;
    return {
      simulatedAnnualRisk: annualRisk,
      sdmPublishedAnchor: anchor,
      deviationRatio: deviation,
      withinTolerance: deviation < 0.6,
    };
  }

  const sdmValidation = validateAgainstSdm();

  // Infeasibility check: can we even meet the target with all days abstain?
  // (Cumulative risk across horizon is 0 if every positive-risk day is A,
  //  so true infeasibility only arises if we permitted partial protection.
  //  The realistic question is: is the user's all-condom plan already over target?)
  const allCondomCumulativeRisk = (() => {
    let surv = 1;
    for (let y = 0; y < years.length; y++) {
      let cycleSurv = 1;
      for (const r of years[y].baseRiskByDay) cycleSurv *= (1 - r * condomResidual);
      const cycleRisk = 1 - cycleSurv;
      surv *= Math.pow(1 - cycleRisk, opts.cyclesPerYear);
    }
    return 1 - surv;
  })();

  const infeasibilityWarning = allCondomCumulativeRisk > opts.targetCumulativeFailure
    ? {
        kind: "target_requires_abstinence",
        allCondomCumulativeRisk,
        message: "Target cannot be met with condoms alone; some abstinence days will be required."
      }
    : null;

  // Warn when any year in horizon has high cycle SD (calendar prediction degrades)
  const highVariabilityYears = years.filter((yr) => yr.cycleSdDays > 4.5).map((yr) => ({ age: yr.age, cycleSdDays: yr.cycleSdDays.toFixed(1) }));
  const perimenopausalWarning = highVariabilityYears.length > 0
    ? {
        kind: "high_cycle_variability",
        affectedYears: highVariabilityYears,
        message: "Some horizon years have cycle SD > 4.5 days. Calendar-based prediction accuracy degrades substantially at this level. Consider body-signal tracking for these years."
      }
    : null;

  const plans = years.map((yr) => Array.from({ length: yr.cycleLengthDays }, () => "U"));
  const yearBurdenPoints = years.map(() => 0);

  function cycleRiskForYear(y) {
    const base = years[y].baseRiskByDay;
    const plan = plans[y];
    let surv = 1;
    for (let d = 0; d < base.length; d++) {
      surv *= (1 - base[d] * multiplierForAction(plan[d]));
    }
    return 1 - surv;
  }

  let cycleRisks = years.map((_, y) => cycleRiskForYear(y));
  let annualSurvival = cycleRisks.map((r) => Math.pow(1 - r, opts.cyclesPerYear));

  function totalSurvival() {
    let s = 1;
    for (let y = 0; y < annualSurvival.length; y++) s *= annualSurvival[y];
    return s;
  }

  function totalCumulativeRisk() {
    return 1 - totalSurvival();
  }

  function timeWeight(yearIndex) {
    return 1 / Math.pow(1 + opts.timePreferenceRate, yearIndex);
  }

  function crowdingWeight(yearIndex) {
    return 1 + opts.yearCrowdingPenalty * (yearBurdenPoints[yearIndex] / years[yearIndex].cycleLengthDays);
  }

  function localAbstainStreakDelta(yearIndex, dayIndex) {
    const plan = plans[yearIndex];
    let left = 0;
    for (let i = dayIndex - 1; i >= 0 && plan[i] === "A"; i--) left++;
    let right = 0;
    for (let i = dayIndex + 1; i < plan.length && plan[i] === "A"; i++) right++;

    const oldPenalty = left * left + right * right;
    const newLen = left + 1 + right;
    let newPenalty = newLen * newLen;
    if (newLen > UX.softMaxAbstainStreak) {
      newPenalty += UX.softMaxStreakPenalty * Math.pow(newLen - UX.softMaxAbstainStreak, 2);
    }

    return UX.streakPenalty * (newPenalty - oldPenalty);
  }

  function currentRiskReductionIfChange(yearIndex, dayIndex, oldAction, newAction) {
    const oldMult = multiplierForAction(oldAction);
    const newMult = multiplierForAction(newAction);
    const base = years[yearIndex].baseRiskByDay[dayIndex];

    // Work in log-survival space for numerical stability.
    // The "benefit" of an upgrade is the change in log-survival, which is
    // well-defined even at tiny survival probabilities.
    const oldDaySurv = 1 - base * oldMult;
    const newDaySurv = 1 - base * newMult;
    if (oldDaySurv <= 0 || newDaySurv <= 0) return 0;
    // Delta log survival for this day's contribution, scaled by cycles/year
    const deltaLogSurv = (Math.log(newDaySurv) - Math.log(oldDaySurv)) * opts.cyclesPerYear;
    return deltaLogSurv;
  }

  function applyChange(yearIndex, dayIndex, oldAction, newAction) {
    plans[yearIndex][dayIndex] = newAction;
    const burden = { U: 0, C: 1, A: 2 };
    yearBurdenPoints[yearIndex] += burden[newAction] - burden[oldAction];

    const oldMult = multiplierForAction(oldAction);
    const newMult = multiplierForAction(newAction);
    const base = years[yearIndex].baseRiskByDay[dayIndex];
    const oldSurv = 1 - cycleRisks[yearIndex];
    const oldDaySurv = 1 - base * oldMult;
    const newDaySurv = 1 - base * newMult;
    const newSurv = oldDaySurv > 0 ? oldSurv * (newDaySurv / oldDaySurv) : 0;
    cycleRisks[yearIndex] = 1 - newSurv;
    annualSurvival[yearIndex] = Math.pow(1 - cycleRisks[yearIndex], opts.cyclesPerYear);
  }

  function bestUpgrade(fromAction, toAction) {
    let best = null;

    for (let y = 0; y < years.length; y++) {
      for (let d = 0; d < years[y].cycleLengthDays; d++) {
        if (plans[y][d] !== fromAction) continue;
        if (years[y].baseRiskByDay[d] <= 0) continue;

        // benefit is positive delta-log-survival (bigger = better improvement)
        const benefit = currentRiskReductionIfChange(y, d, fromAction, toAction);
        if (benefit <= 0) continue;

        let cost;
        if (fromAction === "U" && toAction === "C") {
          cost = UX.condomCost * timeWeight(y) * crowdingWeight(y);
        } else if (fromAction === "C" && toAction === "A") {
          cost = (UX.abstainCost - UX.condomCost) * timeWeight(y) * crowdingWeight(y);
          cost += localAbstainStreakDelta(y, d) * timeWeight(y);
        } else {
          continue;
        }

        const score = benefit / cost;
        if (!best || score > best.score || (score === best.score && benefit > best.benefit)) {
          best = { y, d, score, benefit, cost, fromAction, toAction };
        }
      }
    }

    return best;
  }

  // Condoms first
  let currentRisk = totalCumulativeRisk();
  while (currentRisk > opts.targetCumulativeFailure) {
    const candidate = bestUpgrade("U", "C");
    if (!candidate) break;
    applyChange(candidate.y, candidate.d, "U", "C");
    currentRisk = totalCumulativeRisk();
  }

  // Abstinence only if condoms are not enough
  while (currentRisk > opts.targetCumulativeFailure) {
    const candidate = bestUpgrade("C", "A");
    if (!candidate) break;
    applyChange(candidate.y, candidate.d, "C", "A");
    currentRisk = totalCumulativeRisk();
  }

  // Check if target was actually met (it may not be if every day is already A)
  const targetMet = currentRisk <= opts.targetCumulativeFailure;

  // Always-visible day weights.
  // Raw risk score: normalized within the year.
  // Override cost: approximate same-cycle recovery burden if the user ignores the recommendation.
  function estimateOverrideCostForDay(yearIndex, dayIndex) {
    const action = plans[yearIndex][dayIndex];
    const base = years[yearIndex].baseRiskByDay;

    let addedRisk = 0;
    let label = null;

    if (action === "A") {
      // Most plausible override: sex with a condom on an abstinence day.
      addedRisk = base[dayIndex] * condomResidual;
      label = "Condom";
    } else if (action === "C") {
      // Most plausible override: deciding to go unprotected instead.
      addedRisk = base[dayIndex] * (1 - condomResidual);
      label = "Unprotected";
    } else {
      return { overrideAction: null, condoms: 0, abstinenceDays: 0, note: "No recovery cost shown for recommended unprotected days." };
    }

    // Use SAME-CYCLE approximation: how many tighter days in this cycle would offset that added risk?
    const futureCondomSavings = [];
    const futureAbstainSavings = [];

    for (let d = 0; d < years[yearIndex].cycleLengthDays; d++) {
      if (d === dayIndex) continue;
      const current = plans[yearIndex][d];
      if (current === "U") {
        const save = base[d] * (1 - condomResidual);
        if (save > 0) futureCondomSavings.push(save);
      } else if (current === "C") {
        const save = base[d] * condomResidual;
        if (save > 0) futureAbstainSavings.push(save);
      }
    }

    futureCondomSavings.sort((a, b) => b - a);
    futureAbstainSavings.sort((a, b) => b - a);

    let remaining = addedRisk;
    let condomDays = 0;
    let abstainDays = 0;

    for (const save of futureCondomSavings) {
      if (remaining <= 0) break;
      remaining -= save;
      condomDays++;
    }
    for (const save of futureAbstainSavings) {
      if (remaining <= 0) break;
      remaining -= save;
      abstainDays++;
    }

    const recovered = remaining <= 0;
    return {
      overrideAction: label,
      condoms: condomDays,
      abstinenceDays: abstainDays,
      note: recovered
        ? `Approx. recovery if overridden with ${label}: +${condomDays} condom day(s) and +${abstainDays} abstinence day(s) elsewhere in the same cycle.`
        : `Approx. same-cycle recovery not fully available; this override would likely force a broader replanning of the current cycle and/or future cycles.`
    };
  }

  function normalizeRiskScores(baseRiskByDay) {
    const max = Math.max(...baseRiskByDay, 1e-12);
    return baseRiskByDay.map((r) => Math.round((r / max) * 100));
  }

  function listDaysForAction(planArr, action) {
    const out = [];
    for (let i = 0; i < planArr.length; i++) if (planArr[i] === action) out.push(i + 1);
    return out;
  }

  function countActions(planArr) {
    let U = 0, C = 0, A = 0;
    for (const x of planArr) {
      if (x === "U") U++;
      else if (x === "C") C++;
      else A++;
    }
    return { unprotected: U, condom: C, abstain: A };
  }

  const yearOutputs = years.map((yr, y) => {
    const riskScores = normalizeRiskScores(yr.baseRiskByDay);
    const plan = plans[y].slice();
    const dayWeights = plan.map((action, d) => ({
      day: d + 1,
      recommendedAction: action,
      rawRiskScore: riskScores[d],
      overrideCost: estimateOverrideCostForDay(y, d),
    }));

    return {
      yearIndex: y,
      age: yr.age,
      cycleLengthDays: yr.cycleLengthDays,
      cycleSdDays: yr.cycleSdDays,
      actsPerWeek: yr.actsPerWeek,
      cycleRisk: cycleRisks[y],
      annualRisk: annualFromCycleRisk(cycleRisks[y]),
      counts: countActions(plan),
      groupedDays: {
        unprotected: listDaysForAction(plan, "U"),
        condom: listDaysForAction(plan, "C"),
        abstain: listDaysForAction(plan, "A"),
      },
      dayWeights,
    };
  });

  return {
    optionsUsed: opts,
    derivedUXWeights: UX,
    validation: {
      sdmReference: sdmValidation,
      condomResidualsUsed: condomResiduals,
      selectedCondomResidual: condomResidual,
    },
    achievedCumulativeRisk: totalCumulativeRisk(),
    targetMet,
    warnings: [infeasibilityWarning, perimenopausalWarning].filter(Boolean),
    years: yearOutputs,
  };
}
```

Example:
```javascript
const out = fertilityRiskPlanner({
  ageYears: 34,
  targetCumulativeFailure: 0.02,
  condomMode: "perfect",
  streakAversion: 0.75,
});

console.log("Achieved cumulative risk:", out.achievedCumulativeRisk);
console.log("Target met:", out.targetMet);
console.log("Warnings:", out.warnings);
console.log("Age 34 summary:", out.years[0].counts, out.years[0].groupedDays);
console.log("Day 12 weight:", out.years[0].dayWeights[11]);
```

The current snippet is the best thing to treat as the source of truth now; the earlier narrative example outputs won't match it exactly.
