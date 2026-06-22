# Incident and logging contract (app layer → `UserOptions`)

The Rust core does not encode “incident types.” The **web (or mobile) shell** maps user-reported events into:

1. **`realized_cumulative_risk`** — cumulative pregnancy-risk budget already **consumed** for the current planning horizon (subtracted from `target_cumulative_failure` before optimization; see `effective_cumulative_target` in `planner-core`).
2. **`initial_action_locks`** — `DayOverride` entries in [`types.rs`](../crates/planner-core/src/types.rs): horizon **row index** (`year_index`, 0-based), **cycle day** (1-based), and **observed or intended** action `U` / `C` / `A`.

All numeric “deltas” below are **illustrative v1 placeholders**. They are **not** clinical effectiveness estimates. Product copy must state that users should discuss risk with a qualified clinician; the app is a transparency/planning aid only.

## v1 mapping table (recommended defaults)

| User-reported event | `initial_action_locks` | Increment `realized_cumulative_risk` |
|---------------------|-------------------------|--------------------------------------|
| Had **unprotected** intercourse on an **abstinence** day | Lock that cell to `U` | Add **Δ**: e.g. `min(0.01, that_day_unprotected_cycle_risk × cycles_per_year_annualized_hint)` — *app may use a fixed small cap per event (e.g. 0.002–0.01) instead of recomputing day risk in TS* |
| Had **condom** sex on an **abstinence** day | Lock to `C` | Optional small Δ (e.g. residual of that day) or 0 if treating condom as “as planned tightening” |
| **Condom failure / slip** (was condom day) | Lock to `U` | Add Δ similar to unprotected row, often **larger** than typical U-on-A because peak exposure |
| Unprotected on a **condom** day (ignored plan) | Lock to `U` | Same Δ family as first row |
| **As-lived adherence** (no violation, just logging) | Lock cell to logged `U` / `C` / `A` | **0** |

## Rule of thumb

- **Locks** = “this is what happened (or will happen) on this cycle day; do not move it during optimization.”
- **Realized risk** = “this event already spent part of my **horizon** pregnancy-risk budget.”

Double-counting: if you lock a day to `U` **and** the model already attributes cycle risk to that choice, avoid also adding a huge realized Δ for the same event unless intentionally modeling “extra” shock (e.g. failure). Prefer **one** primary channel: either a lock that reflects behavior **or** a budget burn, not both duplicated without documentation.

## Emergency contraception

Logging “I used EC” should **not** auto-compute efficacy in v1. Use a **journal flag** only; see in-app EC panel and README. Optional small realized Δ is a **policy decision** and must be documented if added.

## Related types

- [`UserOptions`](../crates/planner-core/src/types.rs) — `realized_cumulative_risk`, `initial_action_locks`, `calendar_cycles`.
