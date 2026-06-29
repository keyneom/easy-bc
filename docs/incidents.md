# Incident and logging contract (app layer → `UserOptions`)

> **Updated.** Three parts of this document have been superseded — see
> [`risk-accounting-and-ec.md`](./risk-accounting-and-ec.md):
> 1. **Realized risk is not permanently consumed.** It depletes the budget while
>    a cycle is in flight, then releases on the next confirmed period (no
>    pregnancy). Cumulative risk composes through survival rather than direct
>    subtraction.
> 2. **EC is a dated event, not a numeric credit.** Type and timing are stored,
>    but the planner does not invent an efficacy multiplier.
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
| `plan_b_taken` event | Dated journal event with EC type and optional hours-from-act; no numeric efficacy credit |

The incident aggregator subtracts the plan probability already embedded on an
incident day once. This makes the known act replace the prospective
frequency-spread assumption instead of counting both.

## Emergency contraception

Logging EC does not auto-compute efficacy. The event remains in the day history
and encrypted sync payload; the legacy `ecJournalFlag` remains only for backward
compatibility.

## Related types

- [`UserOptions`](../crates/planner-core/src/types.rs) — `realized_cumulative_risk`, `initial_action_locks`, `calendar_cycles`.
