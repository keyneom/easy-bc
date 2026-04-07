//! Calendar-mode horizons and realized risk budget.

use planner_core::types::{CondomMode, CycleInstance, DayOverride, RecommendedAction, UserOptions};
use planner_core::{effective_cumulative_target, fertility_risk_planner};

#[test]
fn calendar_cycles_two_rows_differ_from_legacy_age_grid() {
    let cc = vec![
        CycleInstance {
            cycle_length_days: 26,
            cycle_sd_days: 2.5,
            acts_per_week: 3.5,
            age_years: 34,
        },
        CycleInstance {
            cycle_length_days: 30,
            cycle_sd_days: 3.0,
            acts_per_week: 3.5,
            age_years: 34,
        },
    ];
    let cal = fertility_risk_planner(UserOptions {
        calendar_cycles: Some(cc),
        target_cumulative_failure: 0.05,
        horizon_years: 1,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .unwrap();
    assert_eq!(cal.years.len(), 2);
    assert_eq!(cal.years[0].cycle_length_days, 26);
    assert_eq!(cal.years[1].cycle_length_days, 30);
    assert!(cal.target_met);
}

#[test]
fn realized_cumulative_risk_tightens_effective_target() {
    let base_opts = UserOptions {
        target_cumulative_failure: 0.05,
        horizon_years: 5,
        realized_cumulative_risk: 0.0,
        ..Default::default()
    };
    let base = fertility_risk_planner(base_opts.clone()).unwrap();

    let tight = fertility_risk_planner(UserOptions {
        realized_cumulative_risk: 0.02,
        ..base_opts
    })
    .unwrap();

    assert!(
        (effective_cumulative_target(&UserOptions {
            realized_cumulative_risk: 0.02,
            target_cumulative_failure: 0.05,
            ..Default::default()
        }) - 0.03)
            .abs()
            < 1e-12
    );
    assert!(
        tight.achieved_cumulative_risk <= base.achieved_cumulative_risk + 1e-9,
        "tighter budget should not increase achieved risk vs looser baseline"
    );
}

#[test]
fn withdrawal_relative_risk_validates() {
    let err = fertility_risk_planner(UserOptions {
        withdrawal_relative_risk: 1.5,
        ..Default::default()
    })
    .unwrap_err();
    assert!(matches!(
        err,
        planner_core::PlannerError::WithdrawalRelativeOutOfRange
    ));
}

#[test]
fn initial_lock_withdrawal_is_preserved() {
    let out = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        target_cumulative_failure: 0.2,
        initial_action_locks: vec![DayOverride {
            year_index: 0,
            day: 5,
            action: RecommendedAction::W,
        }],
        ..Default::default()
    })
    .unwrap();
    assert_eq!(
        out.years[0].day_weights[4].recommended_action,
        RecommendedAction::W
    );
}
