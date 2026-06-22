use planner_core::types::{CondomMode, DayOverride, RecommendedAction, UserOptions};
use planner_core::{fertility_risk_planner, replan_preview};

#[test]
fn replan_empty_matches_baseline() {
    let opts = UserOptions {
        horizon_years: 3,
        ..Default::default()
    };
    let base = fertility_risk_planner(opts.clone()).unwrap();
    let prev = replan_preview(opts, &[]).unwrap();
    assert!(prev.feasible);
    assert_eq!(prev.diffs.len(), 0);
    assert!(
        (prev.preview.achieved_cumulative_risk - base.achieved_cumulative_risk).abs() < 1e-9,
        "empty replan should match baseline risk"
    );
}

#[test]
fn replan_relax_one_day_tracks_diffs_or_feasible() {
    let opts = UserOptions {
        age_years: 30,
        horizon_years: 5,
        target_cumulative_failure: 0.02,
        condom_mode: CondomMode::Perfect,
        ..Default::default()
    };
    let base = fertility_risk_planner(opts.clone()).unwrap();
    let mut target: Option<(i32, i32)> = None;
    'outer: for y in &base.years {
        for d in &y.day_weights {
            if d.recommended_action == RecommendedAction::A {
                target = Some((y.year_index, d.day));
                break 'outer;
            }
        }
    }
    let Some((yi, day)) = target else {
        return;
    };
    let prev = replan_preview(
        opts,
        &[DayOverride {
            year_index: yi,
            day,
            action: RecommendedAction::C,
        }],
    )
    .unwrap();
    assert!(
        !prev.diffs.is_empty() || prev.preview_target_met,
        "should list changes or still meet target"
    );
}

#[test]
fn invalid_override_year_rejected() {
    let opts = UserOptions {
        horizon_years: 2,
        ..Default::default()
    };
    let e = replan_preview(
        opts,
        &[DayOverride {
            year_index: 99,
            day: 1,
            action: RecommendedAction::U,
        }],
    )
    .unwrap_err();
    assert!(matches!(e, planner_core::PlannerError::InvalidDayOverride));
}
