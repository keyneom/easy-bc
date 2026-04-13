//! Parity with `scripts/reference-planner.mjs` (README algorithm).

use planner_core::fertility_risk_planner;
use planner_core::types::{CondomMode, UserOptions};

fn plan_string(year: &planner_core::types::YearOutput) -> String {
    year.day_weights
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
/// Parity with `scripts/reference-planner.mjs` “default_typical” (1% cumulative over horizon, typical condoms).
fn golden_typical_1pct_reference() {
    let out = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.01,
        ..Default::default()
    })
    .unwrap();
    assert!(out.target_met);
    assert!(
        (out.achieved_cumulative_risk - 0.009968536971187048).abs() < 1e-12,
        "risk {}",
        out.achieved_cumulative_risk
    );
    assert_eq!(plan_string(&out.years[0]), "UCAAAAAAAAAAAAAAAAAAACUUUUUU");
    assert_eq!(plan_string(&out.years[1]), "UCAAAAAAAAAAAAAAAAAAACUUUUUU");
    assert!(
        (out.validation.selected_condom_residual - 0.0313468836911448).abs() < 1e-12,
        "residual {}",
        out.validation.selected_condom_residual
    );
}

#[test]
fn golden_example_readme() {
    let opts = UserOptions {
        age_years: 34,
        horizon_years: 2,
        target_cumulative_failure: 0.02,
        condom_mode: CondomMode::Perfect,
        streak_aversion: 0.75,
        ..Default::default()
    };
    let out = fertility_risk_planner(opts).unwrap();
    assert!(out.target_met);
    assert!(
        (out.achieved_cumulative_risk - 0.019993563257089564).abs() < 1e-12,
        "risk {}",
        out.achieved_cumulative_risk
    );
    assert_eq!(plan_string(&out.years[0]), "UCCCCACACACAAACACAACCCUUUUUU");
    assert_eq!(plan_string(&out.years[1]), "UCCCCACACACACACACACCCCUUUUUU");
}

#[test]
fn golden_hold_lifecycle_constant() {
    let opts = UserOptions {
        horizon_years: 3,
        hold_lifecycle_constant: true,
        target_cumulative_failure: 0.01,
        ..Default::default()
    };
    let out = fertility_risk_planner(opts).unwrap();
    assert_eq!(plan_string(&out.years[0]), "UCAAAAAAAAAAAAAAAAAAACUUUUUU");
    assert_eq!(plan_string(&out.years[1]), "UCAAAACAAAAAAAAAAAAAACUUUUUU");
    assert!(
        (out.achieved_cumulative_risk - 0.0059832860540809385).abs() < 1e-12,
        "risk {}",
        out.achieved_cumulative_risk
    );
}

#[test]
fn sdm_validation_annual_risk_matches_js() {
    let out = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.01,
        ..Default::default()
    })
    .unwrap();
    let expected = 0.1136927258950372_f64;
    assert!(
        (out.validation.sdm_reference.simulated_annual_risk - expected).abs() < 1e-12,
        "sdm {}",
        out.validation.sdm_reference.simulated_annual_risk
    );
}
