//! Narrative default: age 34, 20y horizon, 5% cumulative pregnancy-risk budget, 3.5 acts/week, perfect-use condoms.

use planner_core::fertility_risk_planner;
use planner_core::types::{CondomMode, UserOptions};

fn year0_plan(out: &planner_core::types::PlannerResult) -> String {
    out.years[0]
        .day_weights
        .iter()
        .map(|d| match d.recommended_action {
            planner_core::types::RecommendedAction::U => 'U',
            planner_core::types::RecommendedAction::W => 'W',
            planner_core::types::RecommendedAction::C => 'C',
            planner_core::types::RecommendedAction::A => 'A',
        })
        .collect()
}

#[test]
fn scenario_34yo_5pct_over_20y_35_acts_perfect() {
    let out = fertility_risk_planner(UserOptions {
        age_years: 34,
        horizon_years: 20,
        target_cumulative_failure: 0.05,
        acts_per_week: 3.5,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .unwrap();

    assert!(out.target_met, "plan should meet cumulative target");
    assert!(
        out.achieved_cumulative_risk <= 0.05 + 1e-9,
        "achieved {:.6} should not exceed 5%",
        out.achieved_cumulative_risk
    );
    assert!(
        (out.achieved_cumulative_risk - 0.042493871615409295).abs() < 1e-12,
        "golden cumulative risk (if this drifts, re-run narrative_json + compare-narrative.mjs)"
    );

    assert_eq!(out.years[0].age, 34);
    assert_eq!(out.years[0].cycle_length_days, 28);
    assert_eq!(out.years[0].counts.unprotected, 7);
    assert_eq!(out.years[0].counts.condom, 6);
    assert_eq!(out.years[0].counts.abstain, 15);
    assert_eq!(year0_plan(&out), "UCACAACAAAAAAACAAACAACUUUUUU");

    let has_variability = out.warnings.iter().any(|w| {
        matches!(
            w,
            planner_core::types::PlannerWarning::HighCycleVariability { .. }
        )
    });
    assert!(
        has_variability,
        "long horizon should trigger high-SD years warning"
    );
}
