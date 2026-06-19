//! Calendar-mode horizons and realized risk budget.

use planner_core::types::{
    BodySignalInputs, CondomMode, CycleInstance, DayOverride, PersistentMethod, ProtectedDayMethod,
    RecommendedAction, UserOptions, WithdrawalMode,
};
use planner_core::{effective_cumulative_target, fertility_risk_planner};
use std::time::Instant;

#[test]
fn calendar_cycles_two_rows_differ_from_legacy_age_grid() {
    let cc = vec![
        CycleInstance {
            cycle_length_days: 26,
            cycle_sd_days: 2.5,
            acts_per_week: 3.5,
            age_years: 34,
            body_signals: None,
        },
        CycleInstance {
            cycle_length_days: 30,
            cycle_sd_days: 3.0,
            acts_per_week: 3.5,
            age_years: 34,
            body_signals: None,
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
fn calendar_cycles_support_full_long_horizon() {
    let cc = (0..263)
        .map(|_| CycleInstance {
            cycle_length_days: 28,
            cycle_sd_days: 3.9,
            acts_per_week: 3.5,
            age_years: 34,
            body_signals: None,
        })
        .collect();

    let cal = fertility_risk_planner(UserOptions {
        calendar_cycles: Some(cc),
        target_cumulative_failure: 0.05,
        horizon_years: 20,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .unwrap();

    assert_eq!(cal.years.len(), 263);
    assert!(cal.target_met);
}

/// Performance guardrail. The greedy optimizer maintains a per-cycle
/// candidate cache so each upgrade iteration touches one cycle's days
/// rather than rescanning every cycle*day cell. A full 20-year calendar
/// plan must build well under a second; a regression here (e.g. someone
/// reintroducing an all-cells rescan in the hot loop) blows straight
/// past this ceiling.
#[test]
fn full_long_horizon_plan_builds_quickly() {
    let cc = (0..263)
        .map(|_| CycleInstance {
            cycle_length_days: 28,
            cycle_sd_days: 3.9,
            acts_per_week: 3.5,
            age_years: 34,
            body_signals: None,
        })
        .collect();

    let started = Instant::now();
    let cal = fertility_risk_planner(UserOptions {
        calendar_cycles: Some(cc),
        target_cumulative_failure: 0.05,
        horizon_years: 20,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .unwrap();
    let elapsed = started.elapsed();

    assert_eq!(cal.years.len(), 263);
    // Generous ceiling — a release build lands far under this; the point
    // is to catch an order-of-magnitude regression, not to micro-bench.
    assert!(
        elapsed.as_millis() < 1500,
        "263-cycle plan took {elapsed:?}, expected < 1.5s",
    );
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
fn locked_actuals_can_spend_reserve_without_forcing_full_recovery() {
    let calendar_cycles = (0..40)
        .map(|_| CycleInstance {
            cycle_length_days: 28,
            cycle_sd_days: 3.0,
            acts_per_week: 3.5,
            age_years: 34,
            body_signals: None,
        })
        .collect();
    let out = fertility_risk_planner(UserOptions {
        calendar_cycles: Some(calendar_cycles),
        target_cumulative_failure: 0.01,
        condom_mode: CondomMode::Perfect,
        initial_action_locks: vec![DayOverride {
            year_index: 0,
            day: 12,
            action: RecommendedAction::C,
        }],
        ..Default::default()
    })
    .unwrap();

    let budget_used = out.achieved_cumulative_risk / out.options_used.target_cumulative_failure;
    assert!(out.target_met);
    assert!(
        budget_used > 0.85 && budget_used < 0.94,
        "locked actual should spend reserve but remain below recovery ceiling, got {:.1}% used",
        budget_used * 100.0
    );
    assert_eq!(
        out.years[0].day_weights[11].recommended_action,
        RecommendedAction::C
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
        withdrawal_mode: WithdrawalMode::Typical,
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

#[test]
fn calendar_cycles_are_treated_as_literal_cycles() {
    let calendar_cycles = (0..6)
        .map(|_| CycleInstance {
            cycle_length_days: 28,
            cycle_sd_days: 3.0,
            acts_per_week: 3.0,
            age_years: 34,
            body_signals: None,
        })
        .collect();
    let out = fertility_risk_planner(UserOptions {
        calendar_cycles: Some(calendar_cycles),
        target_cumulative_failure: 0.05,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .unwrap();

    assert_eq!(out.years.len(), 6);
    assert!(out.years.iter().all(|year| year.literal_cycle));
    assert!(out
        .years
        .iter()
        .all(|year| (year.effective_cycles_per_year - 1.0).abs() < 1e-12));
    let literal_cumulative_risk = 1.0
        - out
            .years
            .iter()
            .map(|year| 1.0 - year.cycle_risk)
            .product::<f64>();
    assert!(
        (out.achieved_cumulative_risk - literal_cumulative_risk).abs() < 1e-12,
        "calendar rows should compose as literal cycles"
    );
    assert!(out
        .years
        .iter()
        .all(|year| (year.annual_risk - year.cycle_risk).abs() < 1e-12));
}

#[test]
fn longer_cycles_reduce_effective_cycles_per_year_in_legacy_mode() {
    let shorter = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        cycle_length_days: 28,
        hold_lifecycle_constant: true,
        target_cumulative_failure: 0.2,
        ..Default::default()
    })
    .unwrap();
    let longer = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        cycle_length_days: 40,
        hold_lifecycle_constant: true,
        target_cumulative_failure: 0.2,
        ..Default::default()
    })
    .unwrap();

    assert!(longer.years[0].effective_cycles_per_year < shorter.years[0].effective_cycles_per_year);
    assert!((shorter.years[0].effective_cycles_per_year - (365.25 / 28.0)).abs() < 1e-12);
    assert!((longer.years[0].effective_cycles_per_year - (365.25 / 40.0)).abs() < 1e-12);
}

#[test]
fn persistent_methods_lower_the_unprotected_risk_floor() {
    let none = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        target_cumulative_failure: 0.5,
        hold_lifecycle_constant: true,
        persistent_method: PersistentMethod::None,
        ..Default::default()
    })
    .unwrap();
    let implant = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        target_cumulative_failure: 0.5,
        hold_lifecycle_constant: true,
        persistent_method: PersistentMethod::Implant,
        ..Default::default()
    })
    .unwrap();

    assert_eq!(
        none.validation.method_library.persistent_method_residual,
        1.0
    );
    assert!(
        implant.validation.method_library.persistent_method_residual
            < none.validation.method_library.persistent_method_residual
    );
    assert!(implant.achieved_cumulative_risk < none.achieved_cumulative_risk);
}

#[test]
fn protected_day_method_residuals_follow_method_library_ordering() {
    let external = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        protected_day_method: ProtectedDayMethod::ExternalCondom,
        condom_mode: CondomMode::Typical,
        ..Default::default()
    })
    .unwrap();
    let diaphragm = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        protected_day_method: ProtectedDayMethod::Diaphragm,
        condom_mode: CondomMode::Typical,
        ..Default::default()
    })
    .unwrap();
    let internal = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        protected_day_method: ProtectedDayMethod::InternalCondom,
        condom_mode: CondomMode::Typical,
        ..Default::default()
    })
    .unwrap();

    let external_r = external
        .validation
        .method_library
        .protected_day_method_residual;
    let diaphragm_r = diaphragm
        .validation
        .method_library
        .protected_day_method_residual;
    let internal_r = internal
        .validation
        .method_library
        .protected_day_method_residual;
    assert!(external_r < diaphragm_r);
    assert!(diaphragm_r < internal_r);
}

#[test]
fn withdrawal_backup_is_conservative_relative_to_full_independence() {
    let baseline = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        protected_day_method: ProtectedDayMethod::ExternalCondom,
        condom_mode: CondomMode::Typical,
        withdrawal_mode: WithdrawalMode::Typical,
        use_withdrawal_backup_on_protected_days: false,
        ..Default::default()
    })
    .unwrap();
    let layered = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        protected_day_method: ProtectedDayMethod::ExternalCondom,
        condom_mode: CondomMode::Typical,
        withdrawal_mode: WithdrawalMode::Typical,
        use_withdrawal_backup_on_protected_days: true,
        combined_method_independence: 0.35,
        ..Default::default()
    })
    .unwrap();

    let protected = baseline
        .validation
        .method_library
        .protected_day_method_residual;
    let withdrawal = baseline.validation.method_library.withdrawal_residual;
    let ideal_independent = protected * withdrawal;
    let combined = layered
        .validation
        .method_library
        .combined_protected_withdrawal_residual
        .unwrap();

    assert!(combined < protected);
    assert!(combined > ideal_independent);
    assert_eq!(
        layered
            .validation
            .method_library
            .protected_day_method_residual,
        combined
    );
}

#[test]
fn body_signals_surface_a_narrower_ovulation_posterior() {
    let out = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        hold_lifecycle_constant: true,
        body_signals: Some(BodySignalInputs {
            lh_surge_day: Some(13),
            cervical_mucus_peak_day: Some(14),
            basal_body_temperature_shift_day: Some(15),
            wearable_temperature_shift_day: Some(15),
        }),
        ..Default::default()
    })
    .unwrap();

    let summary = out.years[0].signal_summary.as_ref().unwrap();
    assert!(summary.posterior_ovulation_sd_days < 3.0);
    assert!((summary.posterior_ovulation_mean_day - 14.0).abs() < 1.0);
    assert_eq!(summary.signals_used.lh_surge_day, Some(13));
}

#[test]
fn nfp_only_mode_does_not_emit_protected_or_withdrawal_actions() {
    let out = fertility_risk_planner(UserOptions {
        horizon_years: 1,
        target_cumulative_failure: 0.05,
        protected_day_method: ProtectedDayMethod::None,
        withdrawal_mode: WithdrawalMode::None,
        condom_mode: CondomMode::Typical,
        ..Default::default()
    })
    .unwrap();

    assert_eq!(
        out.validation.method_library.protected_day_method,
        ProtectedDayMethod::None
    );
    assert_eq!(
        out.validation.method_library.withdrawal_mode,
        WithdrawalMode::None
    );
    assert!(out.years[0]
        .day_weights
        .iter()
        .all(|day| day.recommended_action != RecommendedAction::C));
    assert!(out.years[0]
        .day_weights
        .iter()
        .all(|day| day.recommended_action != RecommendedAction::W));
}

#[test]
fn unrecoverable_override_cost_does_not_show_partial_day_counts() {
    let locks: Vec<DayOverride> = (1..=28)
        .map(|day| DayOverride {
            year_index: 0,
            day,
            action: RecommendedAction::C,
        })
        .collect();
    let out = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.5,
        condom_mode: CondomMode::Perfect,
        calendar_cycles: Some(vec![CycleInstance {
            cycle_length_days: 28,
            cycle_sd_days: 3.0,
            acts_per_week: 3.5,
            age_years: 34,
            body_signals: None,
        }]),
        initial_action_locks: locks,
        ..Default::default()
    })
    .unwrap();

    let cost = &out.years[0].day_weights[13].override_cost;
    assert!(cost.note.contains("not fully available"));
    assert_eq!(cost.condoms, 0);
    assert_eq!(cost.abstinence_days, 0);
}
