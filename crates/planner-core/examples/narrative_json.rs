//! JSON line for parity with `scripts/compare-narrative.mjs` (34yo, 20y, 5%, 3.5 acts/wk, perfect condoms).

use planner_core::fertility_risk_planner;
use planner_core::types::{CondomMode, RecommendedAction, UserOptions};

fn main() {
    let out = fertility_risk_planner(UserOptions {
        age_years: 34,
        horizon_years: 20,
        target_cumulative_failure: 0.05,
        acts_per_week: 3.5,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .expect("valid options");

    let year0: String = out.years[0]
        .day_weights
        .iter()
        .map(|d| match d.recommended_action {
            RecommendedAction::U => 'U',
            RecommendedAction::W => 'W',
            RecommendedAction::C => 'C',
            RecommendedAction::A => 'A',
        })
        .collect();

    let summary = serde_json::json!({
        "achievedCumulativeRisk": out.achieved_cumulative_risk,
        "targetMet": out.target_met,
        "selectedCondomResidual": out.validation.selected_condom_residual,
        "year0Plan": year0,
        "year0Counts": {
            "unprotected": out.years[0].counts.unprotected,
            "condom": out.years[0].counts.condom,
            "withdrawal": out.years[0].counts.withdrawal,
            "abstain": out.years[0].counts.abstain,
        },
    });
    println!("{}", summary);
}
