# Incident and logging contract (app layer → `UserOptions`)

> **Updated.** Three parts of this document have been superseded — see
> [`risk-accounting-and-ec.md`](./risk-accounting-and-ec.md):
> 1. **Realized risk is not permanently consumed.** It depletes the budget while
>    a cycle is in flight, then releases on the next confirmed period (no
>    pregnancy). Cumulative risk composes through survival rather than direct
>    subtraction.
> 2. **EC is a dated, timed event.** A compatible dose uses the canonical Rust
>    model's least-effective scenario; missing or contradictory timing receives
>    no numeric credit.
> 3. **Discrete incidents are priced per-act**, not per-day-pattern — use
>    `per_act_conception_probability`, not `raw_risk_probability`.

The Rust core does not encode “incident types.” The **web (or mobile) shell** maps user-reported events into:

1. **`realized_cumulative_risk`** — additional pregnancy risk from explicit
   incidents in the unresolved cycle (converted to a remaining future-risk
   budget by `effective_cumulative_target` in `planner-core`).
2. **`initial_action_locks`** — `DayOverride` entries in [`types.rs`](../crates/planner-core/src/types.rs): horizon **row index** (`year_index`, 0-based), **cycle day** (1-based), and **observed or intended** action `U` / `C` / `A`.

## Current mapping

| User-reported data | Planner treatment |
|--------------------|-------------------|
| Ordinary as-lived `U` / `W` / `C` / `A` / `NONE` | Past-day action lock; no separate incident charge |
| `condom_broke` event | One known unprotected act at that day's per-act conception probability |
| `unplanned_unprotected` event | One known unprotected act at that day's per-act conception probability |
| `plan_b_taken` event | Dated EC event; valid type/timing may reduce the most recent compatible incident using the conservative model scenario |

The incident aggregator subtracts the plan probability already embedded on an
incident day once. This makes the known act replace the prospective
frequency-spread assumption instead of counting both.

## Emergency contraception

Logging EC with compatible hours-from-act invokes the canonical Rust estimator.
One dose is assigned only to the most recent compatible incident instead of
reusing its delay for every earlier act. Missing timing, contradictory
date/timing combinations, LNG after 72 hours, and ella/copper-IUD after 120
hours receive no numeric credit. The event remains in day history and encrypted
sync; the legacy `ecJournalFlag` remains only for backward compatibility.

## Related types

- [`UserOptions`](../crates/planner-core/src/types.rs) — `realized_cumulative_risk`, `initial_action_locks`, `calendar_cycles`.
