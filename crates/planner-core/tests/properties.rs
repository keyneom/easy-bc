//! Property-style checks from README testing section (qualitative / monotonic).

use planner_core::fertility_risk_planner;
use planner_core::types::{CondomMode, UserOptions};

fn restrictive_action_days(out: &planner_core::types::PlannerResult) -> i32 {
    out.years
        .iter()
        .map(|y| y.counts.condom + y.counts.withdrawal + y.counts.abstain)
        .sum()
}

#[test]
fn monotonic_higher_target_fewer_restrictions() {
    let base = UserOptions {
        horizon_years: 5,
        ..Default::default()
    };
    let low = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.005,
        ..base.clone()
    })
    .unwrap();
    let high = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.05,
        ..base
    })
    .unwrap();
    assert!(
        restrictive_action_days(&high) <= restrictive_action_days(&low),
        "higher target should not increase C+W+A vs lower"
    );
}

#[test]
fn perfect_condoms_not_stricter_than_typical() {
    let base = UserOptions {
        horizon_years: 5,
        target_cumulative_failure: 0.02,
        ..Default::default()
    };
    let typical = fertility_risk_planner(UserOptions {
        condom_mode: CondomMode::Typical,
        ..base.clone()
    })
    .unwrap();
    let perfect = fertility_risk_planner(UserOptions {
        condom_mode: CondomMode::Perfect,
        ..base
    })
    .unwrap();
    assert!(
        restrictive_action_days(&perfect) <= restrictive_action_days(&typical),
        "perfect use should need fewer C+W+A than typical"
    );
}

#[test]
fn default_legacy_plan_abstinence_does_not_increase_for_equal_length_years() {
    let out = fertility_risk_planner(UserOptions {
        age_years: 34,
        horizon_years: 20,
        target_cumulative_failure: 0.05,
        acts_per_week: 3.5,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    })
    .unwrap();

    for pair in out.years.windows(2) {
        let current = &pair[0];
        let next = &pair[1];
        if current.cycle_length_days == next.cycle_length_days {
            assert!(
                next.counts.abstain <= current.counts.abstain,
                "A days should not increase from age {} to {} when cycle length is unchanged: {} -> {}",
                current.age,
                next.age,
                current.counts.abstain,
                next.counts.abstain
            );
        }
    }
}

#[test]
fn target_zero_negligible_cumulative_risk() {
    let out = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.0,
        horizon_years: 2,
        ..Default::default()
    })
    .unwrap();
    assert!(out.target_met, "target 0 should be met");
    assert!(
        out.achieved_cumulative_risk < 1e-10,
        "near-zero target should drive cumulative risk to ~0, got {}",
        out.achieved_cumulative_risk
    );
}

#[test]
fn lifecycle_evolution_changes_future_cycle_length_vs_hold_constant() {
    let evo = fertility_risk_planner(UserOptions {
        age_years: 40,
        horizon_years: 15,
        target_cumulative_failure: 0.5,
        ..Default::default()
    })
    .unwrap();
    let held = fertility_risk_planner(UserOptions {
        age_years: 40,
        horizon_years: 15,
        target_cumulative_failure: 0.5,
        hold_lifecycle_constant: true,
        ..Default::default()
    })
    .unwrap();
    let last_evo = evo.years.last().unwrap().cycle_length_days;
    let last_held = held.years.last().unwrap().cycle_length_days;
    assert_ne!(
        last_evo, last_held,
        "evolved horizon end should differ from held baseline"
    );
}

#[test]
fn default_lifecycle_cycle_lengths_do_not_bucket_cliff() {
    let out = fertility_risk_planner(UserOptions {
        age_years: 34,
        horizon_years: 20,
        target_cumulative_failure: 0.5,
        ..Default::default()
    })
    .unwrap();
    let lengths: Vec<i32> = out
        .years
        .iter()
        .map(|year| year.cycle_length_days)
        .collect();
    assert!(
        !lengths
            .windows(3)
            .any(|window| window[0] == 28 && window[1] == 27 && window[2] == 28),
        "cycle lengths should not visibly dip and rebound from age buckets: {:?}",
        lengths
    );
    for window in lengths.windows(2) {
        assert!(
            (window[1] - window[0]).abs() <= 3,
            "integer cycle length changed too abruptly: {:?}",
            lengths
        );
    }
}

#[test]
fn heavy_abstinence_warning_when_applicable() {
    let out = fertility_risk_planner(UserOptions {
        target_cumulative_failure: 0.0001,
        horizon_years: 3,
        ..Default::default()
    })
    .unwrap();
    let has_heavy = out.warnings.iter().any(|w| {
        matches!(
            w,
            planner_core::types::PlannerWarning::HeavyAbstinenceBurden { .. }
        )
    });
    let restrictive = restrictive_action_days(&out);
    assert!(
        has_heavy || restrictive > 50,
        "very low target should emit heavy-abstinence warning or require many C+W+A days"
    );
}
