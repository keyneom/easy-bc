//! Product default: cumulative risk budget is over the **full horizon**, not per year.

use planner_core::fertility_risk_planner;
use planner_core::types::UserOptions;

#[test]
fn default_is_5pct_cumulative_over_default_horizon() {
    let o = UserOptions::default();
    assert_eq!(o.horizon_years, 20);
    assert_eq!(o.target_cumulative_failure, 0.05);
    let out = fertility_risk_planner(o).unwrap();
    assert!(
        out.achieved_cumulative_risk <= 0.05 + 1e-9,
        "default run should meet 5% cumulative over {} years",
        out.options_used.horizon_years
    );
    assert!(out.target_met);
}
