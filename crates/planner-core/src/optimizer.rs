//! Whole-horizon greedy optimizer and output assembly. README ~788–1128.
#![allow(clippy::too_many_arguments)]

use crate::biology::{ovulation_posterior, raw_per_day_cycle_risk_for_age};
use crate::condoms::{reference_day_risks, solve_residual_for_annual_target, validate_against_sdm};
use crate::methods::{
    combined_protected_withdrawal_residual, effective_withdrawal_mode, persistent_method_residual,
    protected_day_method_enabled, protected_day_method_residual, withdrawal_mode_enabled,
    withdrawal_residual,
};
use crate::reference_curves::{
    scaled_cycle_length_for_age, scaled_cycle_sd_for_age, scaled_frequency_for_age,
};
use crate::types::{
    ActionCounts, CondomMode, CondomResidualsUsed, DayOverride, DayWeight, DerivedUxWeights,
    HighVariabilityYear, MethodLibraryUsed, OverrideCost, PlanDayDiff, PlannerResult,
    PlannerValidation, PlannerWarning, RecommendedAction, ReplanPreview, SdmValidation,
    SignalSummary, UserOptions, YearOutput,
};
use std::collections::HashSet;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Act {
    U,
    W,
    C,
    A,
}

impl Act {
    fn to_recommended(self) -> RecommendedAction {
        match self {
            Act::U => RecommendedAction::U,
            Act::W => RecommendedAction::W,
            Act::C => RecommendedAction::C,
            Act::A => RecommendedAction::A,
        }
    }

    fn from_recommended(r: RecommendedAction) -> Self {
        match r {
            RecommendedAction::U => Act::U,
            RecommendedAction::W => Act::W,
            RecommendedAction::C => Act::C,
            RecommendedAction::A => Act::A,
        }
    }
}

struct YearData {
    year_index: i32,
    age: i32,
    cycle_length_days: i32,
    cycle_sd_days: f64,
    effective_cycles_per_year: f64,
    literal_cycle: bool,
    acts_per_week: f64,
    base_risk_by_day: Vec<f64>,
    signal_summary: Option<SignalSummary>,
}

struct UxWeights {
    condom_cost: f64,
    abstain_cost: f64,
    streak_penalty: f64,
    soft_max_abstain_streak: i32,
    soft_max_streak_penalty: f64,
}

fn derived_ux(s: f64) -> UxWeights {
    UxWeights {
        condom_cost: 1.0,
        abstain_cost: 8.0 - 3.0 * s,
        streak_penalty: 2.0 + 10.0 * s,
        soft_max_abstain_streak: if s < 0.33 {
            4
        } else if s < 0.66 {
            3
        } else {
            2
        },
        soft_max_streak_penalty: 2.0 + 20.0 * s,
    }
}

fn multiplier_for_action(
    a: Act,
    persistent_residual: f64,
    protected_residual: f64,
    withdrawal_r: f64,
) -> f64 {
    match a {
        Act::U => persistent_residual,
        Act::W => persistent_residual * withdrawal_r,
        Act::C => persistent_residual * protected_residual,
        Act::A => 0.0,
    }
}

fn action_burden_points(a: Act) -> i32 {
    match a {
        Act::U => 0,
        Act::W | Act::C => 1,
        Act::A => 2,
    }
}

fn annual_from_cycle_risk(cycle_risk: f64, cycles_per_year: f64) -> f64 {
    1.0 - (1.0 - cycle_risk).powf(cycles_per_year)
}

const PLANNED_BUDGET_UTILIZATION: f64 = 0.85;
const RECOVERY_BUDGET_UTILIZATION: f64 = 0.94;

fn planning_risk_budget(target: f64) -> f64 {
    if target <= 0.0 {
        0.0
    } else {
        target * PLANNED_BUDGET_UTILIZATION
    }
}

fn recovery_risk_budget(target: f64) -> f64 {
    if target <= 0.0 {
        0.0
    } else {
        target * RECOVERY_BUDGET_UTILIZATION
    }
}

struct Calibrate {
    years: Vec<YearData>,
    condom_residual: f64,
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_residual: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    best_non_abstinent_residual: f64,
    withdrawal_mode_used: crate::types::WithdrawalMode,
    combined_protected_withdrawal_residual: Option<f64>,
    perfect: f64,
    typical: f64,
    ux: UxWeights,
    sdm_validation: SdmValidation,
}

fn calibrate(opts: &UserOptions) -> Result<Calibrate, crate::PlannerError> {
    opts.validate()?;
    let s = opts.streak_aversion;
    let ux = derived_ux(s);
    let ref_day_risks = reference_day_risks(opts);
    let perfect = solve_residual_for_annual_target(
        opts.condom_perfect_annual_failure,
        &ref_day_risks,
        opts.cycles_per_year,
    );
    let typical = solve_residual_for_annual_target(
        opts.condom_typical_annual_failure,
        &ref_day_risks,
        opts.cycles_per_year,
    );
    let condom_residual = match opts.condom_mode {
        CondomMode::Perfect => perfect,
        CondomMode::Typical => typical,
        CondomMode::Custom => opts.custom_condom_residual,
    };
    let persistent_residual =
        persistent_method_residual(opts.persistent_method, &ref_day_risks, opts.cycles_per_year);
    let withdrawal_mode_used = effective_withdrawal_mode(opts);
    let allow_protected_day_method = protected_day_method_enabled(opts.protected_day_method);
    let allow_withdrawal = withdrawal_mode_enabled(withdrawal_mode_used);
    let withdrawal_residual = withdrawal_residual(opts, &ref_day_risks, opts.cycles_per_year);
    let protected_day_residual = protected_day_method_residual(
        opts.protected_day_method,
        condom_residual,
        &ref_day_risks,
        opts.cycles_per_year,
    );
    let combined_protected_withdrawal_residual = if opts.use_withdrawal_backup_on_protected_days
        && allow_protected_day_method
        && allow_withdrawal
    {
        Some(combined_protected_withdrawal_residual(
            protected_day_residual,
            withdrawal_residual,
            opts.combined_method_independence,
        ))
    } else {
        None
    };
    let effective_protected_day_residual =
        combined_protected_withdrawal_residual.unwrap_or(protected_day_residual);
    let best_non_abstinent_residual = if allow_protected_day_method && allow_withdrawal {
        effective_protected_day_residual.min(withdrawal_residual)
    } else if allow_protected_day_method {
        effective_protected_day_residual
    } else if allow_withdrawal {
        withdrawal_residual
    } else {
        1.0
    };
    let mut years: Vec<YearData> = Vec::new();
    if let Some(ref cc) = opts.calendar_cycles {
        if !cc.is_empty() {
            for (y, c) in cc.iter().enumerate() {
                let posterior = ovulation_posterior(
                    c.cycle_length_days,
                    c.cycle_sd_days,
                    c.body_signals.as_ref(),
                    opts,
                );
                let raw = raw_per_day_cycle_risk_for_age(
                    c.age_years,
                    c.cycle_length_days,
                    c.acts_per_week,
                    c.cycle_sd_days,
                    c.body_signals.as_ref(),
                    opts,
                );
                years.push(YearData {
                    year_index: y as i32,
                    age: c.age_years,
                    cycle_length_days: c.cycle_length_days,
                    cycle_sd_days: c.cycle_sd_days,
                    effective_cycles_per_year: 1.0,
                    literal_cycle: true,
                    acts_per_week: c.acts_per_week,
                    base_risk_by_day: raw,
                    signal_summary: c.body_signals.clone().map(|signals| SignalSummary {
                        posterior_ovulation_mean_day: posterior.mean_day,
                        posterior_ovulation_sd_days: posterior.sd_days,
                        signals_used: signals,
                    }),
                });
            }
        }
    }
    if years.is_empty() {
        for y in 0..opts.horizon_years {
            let age = opts.age_years + y;
            let cycle_len = scaled_cycle_length_for_age(age, opts);
            let cycle_sd = scaled_cycle_sd_for_age(age, opts);
            let freq = scaled_frequency_for_age(age, opts);
            let body_signals = if y == 0 {
                opts.body_signals.as_ref()
            } else {
                None
            };
            let posterior = ovulation_posterior(cycle_len, cycle_sd, body_signals, opts);
            let raw =
                raw_per_day_cycle_risk_for_age(age, cycle_len, freq, cycle_sd, body_signals, opts);
            years.push(YearData {
                year_index: y,
                age,
                cycle_length_days: cycle_len,
                cycle_sd_days: cycle_sd,
                effective_cycles_per_year: 365.25 / f64::from(cycle_len),
                literal_cycle: false,
                acts_per_week: freq,
                base_risk_by_day: raw,
                signal_summary: body_signals.cloned().map(|signals| SignalSummary {
                    posterior_ovulation_mean_day: posterior.mean_day,
                    posterior_ovulation_sd_days: posterior.sd_days,
                    signals_used: signals,
                }),
            });
        }
    }
    let sdm_validation = validate_against_sdm(opts);
    Ok(Calibrate {
        years,
        condom_residual,
        persistent_residual,
        protected_day_residual: effective_protected_day_residual,
        withdrawal_residual,
        allow_protected_day_method,
        allow_withdrawal,
        best_non_abstinent_residual,
        withdrawal_mode_used,
        combined_protected_withdrawal_residual,
        perfect,
        typical,
        ux,
        sdm_validation,
    })
}

/// Remaining cumulative risk budget after logged exposures (`realized_cumulative_risk`).
///
/// Depletion is intentional and correct *while a cycle is in flight*: once you've
/// taken on real exposure this horizon, the planner should plan the remaining days
/// tighter to keep the whole-horizon target. The thing that would be wrong is making
/// the depletion *permanent* after a cycle resolves with no pregnancy — that's
/// handled client-side by releasing the just-resolved cycle's contribution on the
/// next confirmed period start (see docs/risk-accounting-and-ec.md). The core's job
/// is simply: given whatever realized exposure the client reports as still-in-play,
/// budget the rest of the horizon against it.
pub fn effective_cumulative_target(opts: &UserOptions) -> f64 {
    let realized = opts.realized_cumulative_risk;
    if realized >= opts.target_cumulative_failure {
        return 0.0;
    }
    // Solve 1 - (1 - realized) * (1 - future) <= target for `future`.
    // Subtracting the two probabilities directly is slightly over-conservative
    // because cumulative pregnancy probabilities compose through survival.
    ((opts.target_cumulative_failure - realized) / (1.0 - realized)).clamp(0.0, 1.0)
}

fn risk_still_above_budget(current_risk: f64, budget: f64) -> bool {
    if budget <= 0.0 {
        current_risk > 1e-10
    } else {
        current_risk > budget
    }
}

fn static_warnings(
    years: &[YearData],
    best_non_abstinent_residual: f64,
    persistent_residual: f64,
    remaining_risk_budget: f64,
) -> Vec<PlannerWarning> {
    let mut warnings: Vec<PlannerWarning> = Vec::new();
    let mut all_condom_surv = 1.0_f64;
    for yr in years {
        let mut cycle_surv = 1.0;
        for r in &yr.base_risk_by_day {
            cycle_surv *= 1.0 - r * persistent_residual * best_non_abstinent_residual;
        }
        let cycle_risk = 1.0 - cycle_surv;
        all_condom_surv *= (1.0 - cycle_risk).powf(yr.effective_cycles_per_year);
    }
    let all_condom_cumulative_risk = 1.0 - all_condom_surv;
    if all_condom_cumulative_risk > remaining_risk_budget {
        warnings.push(PlannerWarning::TargetRequiresAbstinence {
            all_condom_cumulative_risk,
            message:
                "Target cannot be met using only the currently enabled non-abstinent methods; some abstinence days will be required."
                    .to_string(),
        });
    }
    let high_variability_years: Vec<HighVariabilityYear> = years
        .iter()
        .filter(|yr| yr.cycle_sd_days > 4.5)
        .map(|yr| HighVariabilityYear {
            age: yr.age,
            cycle_sd_days: format!("{:.1}", yr.cycle_sd_days),
        })
        .collect();
    if !high_variability_years.is_empty() {
        warnings.push(PlannerWarning::HighCycleVariability {
            affected_years: high_variability_years,
            message: "Some horizon years have cycle SD > 4.5 days. Calendar-based prediction accuracy degrades substantially at this level. Consider body-signal tracking for these years.".to_string(),
        });
    }
    warnings
}

fn recalc_burden(plans: &[Vec<Act>]) -> Vec<i32> {
    plans
        .iter()
        .map(|plan| plan.iter().map(|a| action_burden_points(*a)).sum())
        .collect()
}

fn sync_risk_state(
    years: &[YearData],
    plans: &[Vec<Act>],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
) -> (Vec<f64>, Vec<f64>) {
    let cycle_risks: Vec<f64> = (0..years.len())
        .map(|y| {
            cycle_risk_for_year(
                y,
                years,
                plans,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
            )
        })
        .collect();
    let row_survival: Vec<f64> = cycle_risks
        .iter()
        .enumerate()
        .map(|(idx, r)| (1.0 - r).powf(years[idx].effective_cycles_per_year))
        .collect();
    (cycle_risks, row_survival)
}

fn action_successors(
    cur: Act,
    allow_protected_day_method: bool,
    protected_day_residual: f64,
    allow_withdrawal: bool,
    withdrawal_r: f64,
) -> Vec<Act> {
    match cur {
        Act::U => {
            let mut v = Vec::new();
            if allow_protected_day_method {
                v.push(Act::C);
            }
            if allow_withdrawal && withdrawal_r + 1e-15 < 1.0 {
                v.push(Act::W);
            }
            // NFP-only mode still has abstinence available. Without an
            // intermediate protected/withdrawal action, U must be able to
            // upgrade directly to A or the optimizer cannot reduce risk.
            if v.is_empty() {
                v.push(Act::A);
            }
            v
        }
        Act::W => {
            let mut v = Vec::new();
            if allow_protected_day_method && protected_day_residual + 1e-15 < withdrawal_r {
                v.push(Act::C);
            }
            if allow_withdrawal && withdrawal_r > 1e-15 {
                v.push(Act::A);
            }
            v
        }
        Act::C => {
            if allow_protected_day_method {
                vec![Act::A]
            } else {
                vec![]
            }
        }
        Act::A => vec![],
    }
}

fn greedy_reduce_risk(
    years: &[YearData],
    opts: &UserOptions,
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    ux: &UxWeights,
    mut plans: Vec<Vec<Act>>,
    mut year_burden_points: Vec<i32>,
    mut cycle_risks: Vec<f64>,
    mut row_survival: Vec<f64>,
    risk_budget: f64,
    skip: impl Fn(usize, usize) -> bool,
    relax_to_budget: bool,
) -> (Vec<Vec<Act>>, Vec<i32>, Vec<f64>, Vec<f64>) {
    // Per-cycle best-upgrade cache. Because every benefit/cost term is
    // cycle-local, applying a change in cycle `y` invalidates ONLY that
    // cycle's cached candidate — so each iteration refreshes one cycle's
    // entry (O(days)) instead of rescanning all cycles*days cells. For a
    // 20-year horizon that's the difference between ~14 s and a blink.
    let mut cycle_best: Vec<Option<MarginalCandidate>> = (0..years.len())
        .map(|y| {
            best_marginal_upgrade_for_cycle(
                y,
                years,
                &plans,
                &year_burden_points,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
                allow_protected_day_method,
                allow_withdrawal,
                opts,
                ux,
                &skip,
            )
        })
        .collect();

    let mut current_risk = total_cumulative_risk(&row_survival);
    while risk_still_above_budget(current_risk, risk_budget) {
        // Global best = max over the per-cycle cache (linear in cycles).
        let mut best_y: Option<usize> = None;
        for y in 0..cycle_best.len() {
            let Some(cand) = &cycle_best[y] else { continue };
            let better = match best_y {
                None => true,
                Some(by) => marginal_candidate_beats(cand, cycle_best[by].as_ref().unwrap()),
            };
            if better {
                best_y = Some(y);
            }
        }
        let Some(by) = best_y else {
            break;
        };
        let (cy, cd, cfrom, cto) = {
            let c = cycle_best[by].as_ref().unwrap();
            (c.y, c.d, c.from_action, c.to_action)
        };
        apply_change(
            cy,
            cd,
            cfrom,
            cto,
            &mut plans,
            &mut year_burden_points,
            years,
            persistent_residual,
            protected_day_residual,
            withdrawal_r,
            &mut cycle_risks,
            &mut row_survival,
        );
        current_risk = total_cumulative_risk(&row_survival);
        // Only cycle `cy` changed → refresh just its cached candidate.
        cycle_best[cy] = best_marginal_upgrade_for_cycle(
            cy,
            years,
            &plans,
            &year_burden_points,
            persistent_residual,
            protected_day_residual,
            withdrawal_r,
            allow_protected_day_method,
            allow_withdrawal,
            opts,
            ux,
            &skip,
        );
    }
    if relax_to_budget {
        relax_toward_budget(
            years,
            opts,
            persistent_residual,
            protected_day_residual,
            withdrawal_r,
            allow_protected_day_method,
            allow_withdrawal,
            ux,
            &mut plans,
            &mut year_burden_points,
            &mut cycle_risks,
            &mut row_survival,
            risk_budget,
            skip,
        );
    }
    (plans, year_burden_points, cycle_risks, row_survival)
}

type PlanState = (
    Vec<Vec<Act>>,
    Vec<i32>,
    Vec<f64>,
    Vec<f64>,
    HashSet<(usize, usize)>,
);

fn optimize_with_initial_locks(
    cal: &Calibrate,
    opts: &UserOptions,
    planned_budget: f64,
    recovery_budget: f64,
) -> Result<PlanState, crate::PlannerError> {
    let plans0: Vec<Vec<Act>> = cal
        .years
        .iter()
        .map(|y| vec![Act::U; y.cycle_length_days as usize])
        .collect();
    let year_burden_points = vec![0_i32; cal.years.len()];
    let (cycle_risks, row_survival) = sync_risk_state(
        &cal.years,
        &plans0,
        cal.persistent_residual,
        cal.protected_day_residual,
        cal.withdrawal_residual,
    );

    let (mut plans, mut year_burden_points, mut cycle_risks, mut row_survival) = greedy_reduce_risk(
        &cal.years,
        opts,
        cal.persistent_residual,
        cal.protected_day_residual,
        cal.withdrawal_residual,
        cal.allow_protected_day_method,
        cal.allow_withdrawal,
        &cal.ux,
        plans0,
        year_burden_points,
        cycle_risks,
        row_survival,
        planned_budget,
        |_, _| false,
        true,
    );

    if opts.initial_action_locks.is_empty() {
        enforce_legacy_monotonic_abstinence(
            &cal.years,
            cal.persistent_residual,
            cal.protected_day_residual,
            cal.withdrawal_residual,
            cal.allow_protected_day_method,
            cal.allow_withdrawal,
            &mut plans,
            &mut year_burden_points,
            &mut cycle_risks,
            &mut row_survival,
            planned_budget,
        );
        return Ok((
            plans,
            year_burden_points,
            cycle_risks,
            row_survival,
            HashSet::new(),
        ));
    }

    let skip_initial = apply_initial_action_locks(
        &cal.years,
        opts,
        cal.persistent_residual,
        cal.protected_day_residual,
        cal.withdrawal_residual,
        &mut plans,
        &mut year_burden_points,
        &mut cycle_risks,
        &mut row_survival,
    )?;

    let (plans, year_burden_points, cycle_risks, row_survival) = greedy_reduce_risk(
        &cal.years,
        opts,
        cal.persistent_residual,
        cal.protected_day_residual,
        cal.withdrawal_residual,
        cal.allow_protected_day_method,
        cal.allow_withdrawal,
        &cal.ux,
        plans,
        year_burden_points,
        cycle_risks,
        row_survival,
        recovery_budget,
        |y, d| skip_initial.contains(&(y, d)),
        false,
    );

    Ok((
        plans,
        year_burden_points,
        cycle_risks,
        row_survival,
        skip_initial,
    ))
}

fn enforce_legacy_monotonic_abstinence(
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    plans: &mut [Vec<Act>],
    year_burden_points: &mut [i32],
    cycle_risks: &mut [f64],
    row_survival: &mut [f64],
    risk_budget: f64,
) {
    if years.len() < 2 || years.iter().any(|y| y.literal_cycle) {
        return;
    }

    let max_swaps = plans.iter().map(Vec::len).sum::<usize>();
    for _ in 0..max_swaps {
        let counts: Vec<usize> = plans
            .iter()
            .map(|p| p.iter().filter(|a| **a == Act::A).count())
            .collect();

        let mut applied = false;
        for y in 0..counts.len() - 1 {
            if counts[y + 1] <= counts[y] {
                continue;
            }
            let Some(swap) = best_monotonic_abstinence_swap(
                y,
                years,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
                allow_protected_day_method,
                allow_withdrawal,
                plans,
                cycle_risks,
                row_survival,
                risk_budget,
            ) else {
                continue;
            };
            apply_change(
                swap.early_y,
                swap.early_d,
                swap.early_from,
                Act::A,
                plans,
                year_burden_points,
                years,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
                cycle_risks,
                row_survival,
            );
            apply_change(
                swap.later_y,
                swap.later_d,
                Act::A,
                swap.later_to,
                plans,
                year_burden_points,
                years,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
                cycle_risks,
                row_survival,
            );
            applied = true;
            break;
        }

        if !applied {
            break;
        }
    }
}

fn best_monotonic_abstinence_swap(
    y: usize,
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    plans: &[Vec<Act>],
    cycle_risks: &[f64],
    row_survival: &[f64],
    risk_budget: f64,
) -> Option<MonotonicSwap> {
    let later_y = y + 1;
    let mut best: Option<MonotonicSwap> = None;
    for early_d in 0..plans[y].len() {
        let early_from = plans[y][early_d];
        if early_from == Act::A || years[y].base_risk_by_day[early_d] <= 0.0 {
            continue;
        }
        for (later_d, later_action) in plans[later_y].iter().copied().enumerate() {
            if later_action != Act::A {
                continue;
            }
            for later_to in action_predecessors(
                Act::A,
                allow_protected_day_method,
                protected_day_residual,
                allow_withdrawal,
                withdrawal_r,
            ) {
                let new_risk = cumulative_risk_if_two_changes(
                    y,
                    early_d,
                    early_from,
                    Act::A,
                    later_y,
                    later_d,
                    Act::A,
                    later_to,
                    years,
                    persistent_residual,
                    protected_day_residual,
                    withdrawal_r,
                    cycle_risks,
                    row_survival,
                );
                if new_risk > risk_budget + 1e-12 {
                    continue;
                }
                let burden_delta = action_burden_points(Act::A) - action_burden_points(early_from)
                    + action_burden_points(later_to)
                    - action_burden_points(Act::A);
                let replace = match &best {
                    None => true,
                    Some(b) => {
                        new_risk > b.new_risk + 1e-12
                            || ((new_risk - b.new_risk).abs() <= 1e-12
                                && burden_delta < b.burden_delta)
                    }
                };
                if replace {
                    best = Some(MonotonicSwap {
                        early_y: y,
                        early_d,
                        early_from,
                        later_y,
                        later_d,
                        later_to,
                        new_risk,
                        burden_delta,
                    });
                }
            }
        }
    }
    best
}

fn append_heavy_abstinence(
    years: &[YearData],
    plans: &[Vec<Act>],
    warnings: &mut Vec<PlannerWarning>,
) {
    let mut heavy_abstain_years: Vec<i32> = Vec::new();
    for (y, yr) in years.iter().enumerate() {
        let plan = &plans[y];
        let a_count = plan.iter().filter(|a| **a == Act::A).count() as f64;
        if a_count / f64::from(yr.cycle_length_days) > 0.5 {
            heavy_abstain_years.push(yr.year_index);
        }
    }
    if !heavy_abstain_years.is_empty() {
        warnings.push(PlannerWarning::HeavyAbstinenceBurden {
            message: "This plan requires abstinence on more than half of the days in at least one cycle year. Review whether the risk target and preferences are realistic for you."
                .to_string(),
            affected_year_indices: heavy_abstain_years,
        });
    }
}

fn build_planner_result(
    opts: UserOptions,
    cal: &Calibrate,
    plans: &[Vec<Act>],
    cycle_risks: &[f64],
    row_survival: &[f64],
    mut warnings: Vec<PlannerWarning>,
    target_met: bool,
) -> PlannerResult {
    append_heavy_abstinence(&cal.years, plans, &mut warnings);
    if opts.realized_cumulative_risk > opts.target_cumulative_failure {
        warnings.push(PlannerWarning::RealizedRiskExceedsTarget {
            realized_cumulative_risk: opts.realized_cumulative_risk,
            target_cumulative_failure: opts.target_cumulative_failure,
            message: "Logged in-flight exposure already exceeds the selected cumulative-risk target. The remaining plan has been tightened as far as possible, but it cannot undo risk already taken."
                .to_string(),
        });
    }
    let year_outputs: Vec<YearOutput> = cal
        .years
        .iter()
        .enumerate()
        .map(|(y, yr)| {
            let risk_scores = normalize_risk_scores(&yr.base_risk_by_day);
            let plan = &plans[y];
            // Undo the frequency scaling baked into base_risk_by_day to recover
            // the single-act conception probability. acts_per_day can be 0 (no
            // sex modeled) — then there's no act to price, so per-act is 0.
            let acts_per_day = yr.acts_per_week / 7.0;
            let day_weights: Vec<DayWeight> = plan
                .iter()
                .enumerate()
                .map(|(d, &action)| DayWeight {
                    day: d as i32 + 1,
                    recommended_action: action.to_recommended(),
                    raw_risk_score: risk_scores[d],
                    raw_risk_probability: yr.base_risk_by_day[d] * cal.persistent_residual,
                    per_act_conception_probability: if acts_per_day > 0.0 {
                        yr.base_risk_by_day[d] / acts_per_day
                    } else {
                        0.0
                    },
                    protected_risk_probability: yr.base_risk_by_day[d]
                        * cal.persistent_residual
                        * cal.protected_day_residual,
                    withdrawal_risk_probability: yr.base_risk_by_day[d]
                        * cal.persistent_residual
                        * cal.withdrawal_residual,
                    recommended_risk_probability: yr.base_risk_by_day[d]
                        * multiplier_for_action(
                            action,
                            cal.persistent_residual,
                            cal.protected_day_residual,
                            cal.withdrawal_residual,
                        ),
                    override_cost: estimate_override_cost_for_day(
                        y,
                        d,
                        &cal.years,
                        plan,
                        cal.persistent_residual,
                        cal.protected_day_residual,
                        cal.withdrawal_residual,
                        cal.allow_protected_day_method,
                        cal.allow_withdrawal,
                    ),
                })
                .collect();
            let counts = count_actions(plan);
            YearOutput {
                year_index: yr.year_index,
                age: yr.age,
                cycle_length_days: yr.cycle_length_days,
                cycle_sd_days: yr.cycle_sd_days,
                effective_cycles_per_year: yr.effective_cycles_per_year,
                literal_cycle: yr.literal_cycle,
                acts_per_week: yr.acts_per_week,
                cycle_risk: cycle_risks[y],
                annual_risk: annual_from_cycle_risk(cycle_risks[y], yr.effective_cycles_per_year),
                signal_summary: yr.signal_summary.clone(),
                counts,
                day_weights,
            }
        })
        .collect();
    PlannerResult {
        options_used: opts.clone(),
        derived_ux_weights: DerivedUxWeights {
            condom_cost: cal.ux.condom_cost,
            abstain_cost: cal.ux.abstain_cost,
            streak_penalty: cal.ux.streak_penalty,
            soft_max_abstain_streak: cal.ux.soft_max_abstain_streak,
            soft_max_streak_penalty: cal.ux.soft_max_streak_penalty,
        },
        validation: PlannerValidation {
            sdm_reference: cal.sdm_validation.clone(),
            condom_residuals_used: CondomResidualsUsed {
                perfect: cal.perfect,
                typical: cal.typical,
                custom: opts.custom_condom_residual,
            },
            selected_condom_residual: cal.condom_residual,
            method_library: MethodLibraryUsed {
                persistent_method: opts.persistent_method,
                persistent_method_residual: cal.persistent_residual,
                protected_day_method: opts.protected_day_method,
                protected_day_method_residual: cal.protected_day_residual,
                withdrawal_mode: cal.withdrawal_mode_used,
                withdrawal_residual: cal.withdrawal_residual,
                combined_protected_withdrawal_residual: cal.combined_protected_withdrawal_residual,
            },
        },
        achieved_cumulative_risk: total_cumulative_risk(row_survival),
        target_met,
        warnings,
        years: year_outputs,
    }
}

fn plans_from_result(result: &PlannerResult) -> Vec<Vec<Act>> {
    result
        .years
        .iter()
        .map(|y| {
            y.day_weights
                .iter()
                .map(|d| Act::from_recommended(d.recommended_action))
                .collect()
        })
        .collect()
}

fn validate_action_locks(
    years: &[YearData],
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    locks: &[DayOverride],
) -> Result<(), crate::PlannerError> {
    for o in locks {
        let y = o.year_index as usize;
        if y >= years.len() {
            return Err(crate::PlannerError::InvalidDayOverride);
        }
        let cl = years[y].cycle_length_days;
        if o.day < 1 || o.day > cl {
            return Err(crate::PlannerError::InvalidDayOverride);
        }
        match o.action {
            RecommendedAction::C if !allow_protected_day_method => {
                return Err(crate::PlannerError::InvalidDayOverride);
            }
            RecommendedAction::W if !allow_withdrawal => {
                return Err(crate::PlannerError::InvalidDayOverride);
            }
            _ => {}
        }
    }
    Ok(())
}

fn apply_initial_action_locks(
    years: &[YearData],
    opts: &UserOptions,
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    plans: &mut [Vec<Act>],
    year_burden_points: &mut [i32],
    cycle_risks: &mut [f64],
    row_survival: &mut [f64],
) -> Result<HashSet<(usize, usize)>, crate::PlannerError> {
    validate_action_locks(
        years,
        protected_day_method_enabled(opts.protected_day_method),
        withdrawal_mode_enabled(effective_withdrawal_mode(opts)),
        &opts.initial_action_locks,
    )?;
    let mut skip = HashSet::new();
    let mut sorted: Vec<&DayOverride> = opts.initial_action_locks.iter().collect();
    sorted.sort_by_key(|o| (o.year_index, o.day));
    for o in sorted {
        let y = o.year_index as usize;
        let d = o.day as usize - 1;
        skip.insert((y, d));
        let target = Act::from_recommended(o.action);
        let cur = plans[y][d];
        if cur != target {
            apply_change(
                y,
                d,
                cur,
                target,
                plans,
                year_burden_points,
                years,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
                cycle_risks,
                row_survival,
            );
        }
    }
    Ok(skip)
}

fn diff_plans(baseline: &PlannerResult, preview: &PlannerResult) -> Vec<PlanDayDiff> {
    let mut out = Vec::new();
    for (yb, yp) in baseline.years.iter().zip(preview.years.iter()) {
        for (db, dp) in yb.day_weights.iter().zip(yp.day_weights.iter()) {
            if db.recommended_action != dp.recommended_action {
                out.push(PlanDayDiff {
                    year_index: yb.year_index,
                    day: db.day,
                    baseline_action: db.recommended_action,
                    preview_action: dp.recommended_action,
                });
            }
        }
    }
    out
}

/// Optimal plan, then apply user `overrides` (locked) and add U→C / C→A upgrades on **unlocked** days until the cumulative target is met again (if possible).
pub fn replan_preview(
    opts: UserOptions,
    overrides: &[DayOverride],
) -> Result<ReplanPreview, crate::PlannerError> {
    let cal = calibrate(&opts)?;
    validate_action_locks(
        &cal.years,
        cal.allow_protected_day_method,
        cal.allow_withdrawal,
        &opts.initial_action_locks,
    )?;
    validate_action_locks(
        &cal.years,
        cal.allow_protected_day_method,
        cal.allow_withdrawal,
        overrides,
    )?;
    let eff = effective_cumulative_target(&opts);
    let planning_eff = planning_risk_budget(eff);
    let recovery_eff = recovery_risk_budget(eff);
    let warnings = static_warnings(
        &cal.years,
        cal.best_non_abstinent_residual,
        cal.persistent_residual,
        eff,
    );
    let (plans, _, cr, asurv, skip_initial) =
        optimize_with_initial_locks(&cal, &opts, planning_eff, recovery_eff)?;
    let baseline_target_met = overall_target_met(&opts, total_cumulative_risk(&asurv));
    let baseline = build_planner_result(
        opts.clone(),
        &cal,
        &plans,
        &cr,
        &asurv,
        warnings,
        baseline_target_met,
    );

    let mut locked: HashSet<(usize, usize)> = skip_initial;
    let mut plans_p = plans_from_result(&baseline);
    for o in overrides {
        let yi = o.year_index as usize;
        if yi >= plans_p.len() {
            return Err(crate::PlannerError::InvalidDayOverride);
        }
        let di = o.day as usize;
        if di == 0 || di > plans_p[yi].len() {
            return Err(crate::PlannerError::InvalidDayOverride);
        }
        let d0 = di - 1;
        locked.insert((yi, d0));
        plans_p[yi][d0] = Act::from_recommended(o.action);
    }

    let burden = recalc_burden(&plans_p);
    let (cr0, as0) = sync_risk_state(
        &cal.years,
        &plans_p,
        cal.persistent_residual,
        cal.protected_day_residual,
        cal.withdrawal_residual,
    );
    let (plans_pv, _, cr_p, as_p) = greedy_reduce_risk(
        &cal.years,
        &opts,
        cal.persistent_residual,
        cal.protected_day_residual,
        cal.withdrawal_residual,
        cal.allow_protected_day_method,
        cal.allow_withdrawal,
        &cal.ux,
        plans_p,
        burden,
        cr0,
        as0,
        recovery_eff,
        |y, d| locked.contains(&(y, d)),
        false,
    );
    let preview_target_met = overall_target_met(&opts, total_cumulative_risk(&as_p));
    let feasible = preview_target_met;
    let message = if !feasible {
        Some(
            "Cannot bring cumulative risk back to your target while keeping your chosen days fixed. Try tightening other days, removing a lock, or raising the risk budget."
                .to_string(),
        )
    } else {
        None
    };
    let pw = static_warnings(
        &cal.years,
        cal.best_non_abstinent_residual,
        cal.persistent_residual,
        eff,
    );
    let preview = build_planner_result(opts, &cal, &plans_pv, &cr_p, &as_p, pw, preview_target_met);
    let diffs = diff_plans(&baseline, &preview);
    Ok(ReplanPreview {
        baseline,
        preview,
        preview_target_met,
        feasible,
        message,
        diffs,
    })
}

pub fn fertility_risk_planner(opts: UserOptions) -> Result<PlannerResult, crate::PlannerError> {
    let cal = calibrate(&opts)?;
    validate_action_locks(
        &cal.years,
        cal.allow_protected_day_method,
        cal.allow_withdrawal,
        &opts.initial_action_locks,
    )?;
    let eff = effective_cumulative_target(&opts);
    let planning_eff = planning_risk_budget(eff);
    let recovery_eff = recovery_risk_budget(eff);
    let warnings = static_warnings(
        &cal.years,
        cal.best_non_abstinent_residual,
        cal.persistent_residual,
        eff,
    );
    let (plans, _, cr, asurv, _) =
        optimize_with_initial_locks(&cal, &opts, planning_eff, recovery_eff)?;
    let target_met = overall_target_met(&opts, total_cumulative_risk(&asurv));
    Ok(build_planner_result(
        opts, &cal, &plans, &cr, &asurv, warnings, target_met,
    ))
}

fn total_cumulative_risk(row_survival: &[f64]) -> f64 {
    let mut s = 1.0_f64;
    for surv in row_survival {
        s *= *surv;
    }
    1.0 - s
}

/// Strict `achieved <= target` fails for `target == 0` when the optimizer cannot register
/// sub-ULP marginal improvements (ln-day ratios round to zero) while cumulative risk is
/// still slightly positive. Treat such outcomes as meeting a zero target.
fn cumulative_target_met(achieved: f64, target: f64) -> bool {
    if target <= 0.0 {
        achieved <= 1e-10
    } else {
        achieved <= target
    }
}

fn overall_target_met(opts: &UserOptions, future_risk: f64) -> bool {
    let realized = opts.realized_cumulative_risk.clamp(0.0, 1.0);
    let future = future_risk.clamp(0.0, 1.0);
    let combined = 1.0 - (1.0 - realized) * (1.0 - future);
    cumulative_target_met(combined, opts.target_cumulative_failure)
}

fn cycle_risk_for_year(
    y: usize,
    years: &[YearData],
    plans: &[Vec<Act>],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
) -> f64 {
    let base = &years[y].base_risk_by_day;
    let plan = &plans[y];
    let mut surv = 1.0;
    for d in 0..base.len() {
        surv *= 1.0
            - base[d]
                * multiplier_for_action(
                    plan[d],
                    persistent_residual,
                    protected_day_residual,
                    withdrawal_r,
                );
    }
    (1.0 - surv).clamp(0.0, 1.0)
}

struct MarginalCandidate {
    y: usize,
    d: usize,
    from_action: Act,
    to_action: Act,
    score: f64,
    benefit: f64,
}

struct RelaxCandidate {
    y: usize,
    d: usize,
    from_action: Act,
    to_action: Act,
    score: f64,
    relief: f64,
}

struct MonotonicSwap {
    early_y: usize,
    early_d: usize,
    early_from: Act,
    later_y: usize,
    later_d: usize,
    later_to: Act,
    new_risk: f64,
    burden_delta: i32,
}

/// Relative UX cost of withdrawal steps vs condom (README lattice).
const U_TO_WITHDRAWAL_COST_FRAC: f64 = 0.62;
const WITHDRAWAL_TO_CONDOM_COST_FRAC: f64 = 0.42;

fn marginal_upgrade_cost(
    y: usize,
    d: usize,
    from_action: Act,
    to_action: Act,
    years: &[YearData],
    plans: &[Vec<Act>],
    year_burden_points: &[i32],
    opts: &UserOptions,
    ux: &UxWeights,
) -> Option<f64> {
    let tw = time_weight(y, opts);
    let cw = crowding_weight(y, years, year_burden_points, opts);
    let mut cost = match (from_action, to_action) {
        (Act::U, Act::W) => ux.condom_cost * U_TO_WITHDRAWAL_COST_FRAC * tw * cw,
        (Act::U, Act::C) => ux.condom_cost * tw * cw,
        (Act::W, Act::C) => ux.condom_cost * WITHDRAWAL_TO_CONDOM_COST_FRAC * tw * cw,
        (Act::C, Act::A) => {
            let mut c = (ux.abstain_cost - ux.condom_cost) * tw * cw;
            c += local_abstain_streak_delta(y, d, plans, ux) * tw;
            c
        }
        (Act::W, Act::A) => {
            let mut c = (ux.abstain_cost - ux.condom_cost * U_TO_WITHDRAWAL_COST_FRAC) * tw * cw;
            c += local_abstain_streak_delta(y, d, plans, ux) * tw;
            c
        }
        (Act::U, Act::A) => {
            let mut c = ux.abstain_cost * tw * cw;
            c += local_abstain_streak_delta(y, d, plans, ux) * tw;
            c
        }
        _ => return None,
    };
    if cost <= 0.0 {
        cost = 1e-18;
    }
    Some(cost)
}

/// True when candidate `c` should replace the current best `b`: strictly
/// higher score, or an effective score tie broken by higher absolute
/// benefit. Centralised so the per-cycle scan and the global merge use
/// identical tie-break semantics.
fn marginal_candidate_beats(c: &MarginalCandidate, b: &MarginalCandidate) -> bool {
    c.score > b.score + 1e-18
        || ((c.score - b.score).abs() <= 1e-18 && c.benefit > b.benefit + 1e-18)
}

/// Best single upgrade within ONE cycle. Every term in both the benefit
/// (`current_risk_reduction_if_change`) and the cost (`marginal_upgrade_cost`
/// → `time_weight` / `crowding_weight` / `local_abstain_streak_delta`) reads
/// only cycle `y`'s state, so a change applied in cycle `y` invalidates only
/// this cycle's result — see `greedy_reduce_risk`'s per-cycle cache.
fn best_marginal_upgrade_for_cycle<F: Fn(usize, usize) -> bool>(
    y: usize,
    years: &[YearData],
    plans: &[Vec<Act>],
    year_burden_points: &[i32],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    opts: &UserOptions,
    ux: &UxWeights,
    skip: &F,
) -> Option<MarginalCandidate> {
    let mut best: Option<MarginalCandidate> = None;
    for d in 0..years[y].cycle_length_days as usize {
        if skip(y, d) {
            continue;
        }
        let cur = plans[y][d];
        if years[y].base_risk_by_day[d] <= 0.0 {
            continue;
        }
        for &to in action_successors(
            cur,
            allow_protected_day_method,
            protected_day_residual,
            allow_withdrawal,
            withdrawal_r,
        )
        .iter()
        {
            let benefit = current_risk_reduction_if_change(
                y,
                d,
                cur,
                to,
                years,
                persistent_residual,
                protected_day_residual,
                withdrawal_r,
            );
            if benefit <= 0.0 {
                continue;
            }
            let Some(cost) =
                marginal_upgrade_cost(y, d, cur, to, years, plans, year_burden_points, opts, ux)
            else {
                continue;
            };
            let candidate = MarginalCandidate {
                y,
                d,
                from_action: cur,
                to_action: to,
                score: benefit / cost,
                benefit,
            };
            if best
                .as_ref()
                .is_none_or(|b| marginal_candidate_beats(&candidate, b))
            {
                best = Some(candidate);
            }
        }
    }
    best
}

fn action_predecessors(
    cur: Act,
    allow_protected_day_method: bool,
    protected_day_residual: f64,
    allow_withdrawal: bool,
    withdrawal_r: f64,
) -> Vec<Act> {
    match cur {
        Act::U => vec![],
        Act::W => vec![Act::U],
        Act::C => {
            let mut v = vec![Act::U];
            if allow_withdrawal && protected_day_residual + 1e-15 < withdrawal_r {
                v.push(Act::W);
            }
            v
        }
        Act::A => {
            let mut v = Vec::new();
            if allow_protected_day_method {
                v.push(Act::C);
            }
            if allow_withdrawal && withdrawal_r > 1e-15 {
                v.push(Act::W);
            }
            if v.is_empty() {
                v.push(Act::U);
            }
            v
        }
    }
}

fn relax_toward_budget(
    years: &[YearData],
    opts: &UserOptions,
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    ux: &UxWeights,
    plans: &mut [Vec<Act>],
    year_burden_points: &mut [i32],
    cycle_risks: &mut [f64],
    row_survival: &mut [f64],
    risk_budget: f64,
    skip: impl Fn(usize, usize) -> bool,
) {
    loop {
        let current_risk = total_cumulative_risk(row_survival);
        if current_risk >= risk_budget {
            break;
        }
        let Some(c) = best_marginal_downgrade(
            years,
            plans,
            year_burden_points,
            persistent_residual,
            protected_day_residual,
            withdrawal_r,
            allow_protected_day_method,
            allow_withdrawal,
            opts,
            ux,
            cycle_risks,
            row_survival,
            risk_budget,
            &skip,
        ) else {
            break;
        };
        apply_change(
            c.y,
            c.d,
            c.from_action,
            c.to_action,
            plans,
            year_burden_points,
            years,
            persistent_residual,
            protected_day_residual,
            withdrawal_r,
            cycle_risks,
            row_survival,
        );
    }
}

fn best_marginal_downgrade(
    years: &[YearData],
    plans: &[Vec<Act>],
    year_burden_points: &[i32],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
    opts: &UserOptions,
    ux: &UxWeights,
    cycle_risks: &[f64],
    row_survival: &[f64],
    risk_budget: f64,
    skip: impl Fn(usize, usize) -> bool,
) -> Option<RelaxCandidate> {
    // Precompute the running product once; `cumulative_risk_if_change` then
    // evaluates each candidate in O(1) instead of re-multiplying every row.
    let total_survival: f64 = row_survival.iter().product();
    let current_risk = 1.0 - total_survival;
    let mut best: Option<RelaxCandidate> = None;
    for y in 0..years.len() {
        for d in 0..years[y].cycle_length_days as usize {
            if skip(y, d) || years[y].base_risk_by_day[d] <= 0.0 {
                continue;
            }
            let cur = plans[y][d];
            for &to in action_predecessors(
                cur,
                allow_protected_day_method,
                protected_day_residual,
                allow_withdrawal,
                withdrawal_r,
            )
            .iter()
            {
                let new_risk = cumulative_risk_if_change(
                    y,
                    d,
                    cur,
                    to,
                    years,
                    persistent_residual,
                    protected_day_residual,
                    withdrawal_r,
                    cycle_risks,
                    row_survival,
                    total_survival,
                );
                let risk_increase = new_risk - current_risk;
                if risk_increase <= 1e-18 || new_risk > risk_budget + 1e-12 {
                    continue;
                }
                let Some(relief) = marginal_upgrade_cost(
                    y,
                    d,
                    to,
                    cur,
                    years,
                    plans,
                    year_burden_points,
                    opts,
                    ux,
                ) else {
                    continue;
                };
                if relief <= 0.0 {
                    continue;
                }
                let score = relief / risk_increase;
                let replace = match &best {
                    None => true,
                    Some(b) => {
                        score > b.score + 1e-18
                            || ((score - b.score).abs() <= 1e-18 && relief > b.relief + 1e-18)
                    }
                };
                if replace {
                    best = Some(RelaxCandidate {
                        y,
                        d,
                        from_action: cur,
                        to_action: to,
                        score,
                        relief,
                    });
                }
            }
        }
    }
    best
}

/// Cumulative risk if a single (y, d) cell changed action.
///
/// `total_survival` is the caller's precomputed `∏ row_survival`. We swap
/// row `y`'s factor out of the running product in O(1) — `total / old_row *
/// new_row` — instead of re-multiplying all ~cycles rows on every candidate
/// evaluation. Falls back to a full product only when row `y`'s survival is
/// degenerate (≈0), where the division would be unstable.
fn cumulative_risk_if_change(
    y: usize,
    d: usize,
    old_action: Act,
    new_action: Act,
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    cycle_risks: &[f64],
    row_survival: &[f64],
    total_survival: f64,
) -> f64 {
    let old_mult = multiplier_for_action(
        old_action,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
    );
    let new_mult = multiplier_for_action(
        new_action,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
    );
    let base = years[y].base_risk_by_day[d];
    let old_surv = 1.0 - cycle_risks[y];
    let old_day_surv = 1.0 - base * old_mult;
    let new_day_surv = 1.0 - base * new_mult;
    let new_cycle_surv = if old_day_surv > 0.0 {
        old_surv * (new_day_surv / old_day_surv)
    } else {
        0.0
    };
    let new_cycle_risk = (1.0 - new_cycle_surv).clamp(0.0, 1.0);
    let new_row_survival = (1.0 - new_cycle_risk).powf(years[y].effective_cycles_per_year);
    let old_row = row_survival[y];
    let survival = if old_row > 1e-12 {
        total_survival / old_row * new_row_survival
    } else {
        let mut s = 1.0_f64;
        for (idx, row) in row_survival.iter().enumerate() {
            s *= if idx == y { new_row_survival } else { *row };
        }
        s
    };
    1.0 - survival
}

fn cumulative_risk_if_two_changes(
    y1: usize,
    d1: usize,
    old_action1: Act,
    new_action1: Act,
    y2: usize,
    d2: usize,
    old_action2: Act,
    new_action2: Act,
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    cycle_risks: &[f64],
    row_survival: &[f64],
) -> f64 {
    let mut cycle_risks = cycle_risks.to_vec();
    let mut row_survival = row_survival.to_vec();
    apply_risk_change_only(
        y1,
        d1,
        old_action1,
        new_action1,
        years,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
        &mut cycle_risks,
        &mut row_survival,
    );
    apply_risk_change_only(
        y2,
        d2,
        old_action2,
        new_action2,
        years,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
        &mut cycle_risks,
        &mut row_survival,
    );
    total_cumulative_risk(&row_survival)
}

fn time_weight(year_index: usize, opts: &UserOptions) -> f64 {
    1.0 / (1.0 + opts.time_preference_rate).powi(year_index as i32)
}

fn crowding_weight(
    year_index: usize,
    years: &[YearData],
    year_burden_points: &[i32],
    opts: &UserOptions,
) -> f64 {
    1.0 + opts.year_crowding_penalty
        * (f64::from(year_burden_points[year_index])
            / f64::from(years[year_index].cycle_length_days))
}

fn local_abstain_streak_delta(
    year_index: usize,
    day_index: usize,
    plans: &[Vec<Act>],
    ux: &UxWeights,
) -> f64 {
    let plan = &plans[year_index];
    let mut left = 0_i32;
    let mut i = day_index as i32 - 1;
    while i >= 0 && plan[i as usize] == Act::A {
        left += 1;
        i -= 1;
    }
    let mut right = 0_i32;
    let mut j = day_index + 1;
    while j < plan.len() && plan[j] == Act::A {
        right += 1;
        j += 1;
    }

    let left_f = f64::from(left);
    let right_f = f64::from(right);
    let old_penalty = left_f * left_f + right_f * right_f;
    let new_len = left + 1 + right;
    let new_len_f = f64::from(new_len);
    let mut new_penalty = new_len_f * new_len_f;
    if new_len > ux.soft_max_abstain_streak {
        let excess = f64::from(new_len - ux.soft_max_abstain_streak);
        new_penalty += ux.soft_max_streak_penalty * excess * excess;
    }

    ux.streak_penalty * (new_penalty - old_penalty)
}

fn current_risk_reduction_if_change(
    year_index: usize,
    day_index: usize,
    old_action: Act,
    new_action: Act,
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
) -> f64 {
    let old_mult = multiplier_for_action(
        old_action,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
    );
    let new_mult = multiplier_for_action(
        new_action,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
    );
    let base = years[year_index].base_risk_by_day[day_index];
    let old_day_surv = 1.0 - base * old_mult;
    let new_day_surv = 1.0 - base * new_mult;
    if old_day_surv <= 0.0 || new_day_surv <= 0.0 {
        return 0.0;
    }
    (new_day_surv.ln() - old_day_surv.ln()) * years[year_index].effective_cycles_per_year
}

fn apply_change(
    y: usize,
    d: usize,
    old_action: Act,
    new_action: Act,
    plans: &mut [Vec<Act>],
    year_burden_points: &mut [i32],
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    cycle_risks: &mut [f64],
    row_survival: &mut [f64],
) {
    plans[y][d] = new_action;
    year_burden_points[y] += action_burden_points(new_action) - action_burden_points(old_action);
    apply_risk_change_only(
        y,
        d,
        old_action,
        new_action,
        years,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
        cycle_risks,
        row_survival,
    );
}

fn apply_risk_change_only(
    y: usize,
    d: usize,
    old_action: Act,
    new_action: Act,
    years: &[YearData],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    cycle_risks: &mut [f64],
    row_survival: &mut [f64],
) {
    let old_mult = multiplier_for_action(
        old_action,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
    );
    let new_mult = multiplier_for_action(
        new_action,
        persistent_residual,
        protected_day_residual,
        withdrawal_r,
    );
    let base = years[y].base_risk_by_day[d];
    let old_surv = 1.0 - cycle_risks[y];
    let old_day_surv = 1.0 - base * old_mult;
    let new_day_surv = 1.0 - base * new_mult;
    let new_surv = if old_day_surv > 0.0 {
        old_surv * (new_day_surv / old_day_surv)
    } else {
        0.0
    };
    let cr = (1.0 - new_surv).clamp(0.0, 1.0);
    cycle_risks[y] = cr;
    row_survival[y] = (1.0 - cr).powf(years[y].effective_cycles_per_year);
}

fn estimate_override_cost_for_day(
    year_index: usize,
    day_index: usize,
    years: &[YearData],
    plan: &[Act],
    persistent_residual: f64,
    protected_day_residual: f64,
    withdrawal_r: f64,
    allow_protected_day_method: bool,
    allow_withdrawal: bool,
) -> OverrideCost {
    let action = plan[day_index];
    let base = &years[year_index].base_risk_by_day;

    let (added_risk, label_opt) = match action {
        Act::A => {
            if allow_protected_day_method {
                (
                    base[day_index] * persistent_residual * protected_day_residual,
                    Some("Protected method"),
                )
            } else if allow_withdrawal {
                (
                    base[day_index] * persistent_residual * withdrawal_r,
                    Some("Withdrawal"),
                )
            } else {
                (base[day_index] * persistent_residual, Some("Unprotected"))
            }
        }
        Act::C => (
            base[day_index] * persistent_residual * (1.0 - protected_day_residual),
            Some("Unprotected"),
        ),
        Act::W => (
            base[day_index] * persistent_residual * (1.0 - withdrawal_r),
            Some("Unprotected"),
        ),
        Act::U => {
            return OverrideCost {
                override_action: None,
                condoms: 0,
                abstinence_days: 0,
                recovered: true,
                note: "No recovery cost shown for recommended unprotected days.".to_string(),
            };
        }
    };

    let label = label_opt.unwrap();
    let mut future_condom_savings: Vec<f64> = Vec::new();
    let mut future_abstain_savings: Vec<f64> = Vec::new();

    for d in 0..years[year_index].cycle_length_days as usize {
        if d == day_index {
            continue;
        }
        match plan[d] {
            Act::U => {
                if allow_protected_day_method {
                    let save = base[d] * persistent_residual * (1.0 - protected_day_residual);
                    if save > 0.0 {
                        future_condom_savings.push(save);
                    }
                }
            }
            Act::W => {
                if allow_protected_day_method {
                    let save_c =
                        base[d] * persistent_residual * (withdrawal_r - protected_day_residual);
                    if save_c > 0.0 {
                        future_condom_savings.push(save_c);
                    }
                }
                let save_a = base[d] * persistent_residual * withdrawal_r;
                if save_a > 0.0 {
                    future_abstain_savings.push(save_a);
                }
            }
            Act::C => {
                let save = base[d] * persistent_residual * protected_day_residual;
                if save > 0.0 {
                    future_abstain_savings.push(save);
                }
            }
            Act::A => {}
        }
    }

    future_condom_savings.sort_by(|a, b| b.partial_cmp(a).unwrap_or(std::cmp::Ordering::Equal));
    future_abstain_savings.sort_by(|a, b| b.partial_cmp(a).unwrap_or(std::cmp::Ordering::Equal));

    let mut remaining = added_risk;
    let mut condom_days = 0_i32;
    let mut abstain_days = 0_i32;

    for save in &future_condom_savings {
        if remaining <= 0.0 {
            break;
        }
        remaining -= save;
        condom_days += 1;
    }
    for save in &future_abstain_savings {
        if remaining <= 0.0 {
            break;
        }
        remaining -= save;
        abstain_days += 1;
    }

    let recovered = remaining <= 0.0;
    let (display_condom_days, display_abstinence_days, note) = if recovered {
        (
            condom_days,
            abstain_days,
            format!(
                "Approx. reserve recovery if overridden with {}: +{} condom day(s) and +{} abstinence day(s) elsewhere in the same cycle to restore the plan's risk cushion.",
                label, condom_days, abstain_days
            ),
        )
    } else {
        (
            0,
            0,
            "Approx. same-cycle reserve recovery is not fully available; this override would spend risk reserve and may require broader replanning of the current cycle and/or future cycles to restore the cushion.".to_string(),
        )
    };

    OverrideCost {
        override_action: Some(label.to_string()),
        condoms: display_condom_days,
        abstinence_days: display_abstinence_days,
        recovered,
        note,
    }
}

fn normalize_risk_scores(base_risk_by_day: &[f64]) -> Vec<i32> {
    let max = base_risk_by_day.iter().copied().fold(1e-12_f64, f64::max);
    base_risk_by_day
        .iter()
        .map(|r| ((r / max) * 100.0).round() as i32)
        .collect()
}

fn count_actions(plan: &[Act]) -> ActionCounts {
    let mut u = 0;
    let mut c = 0;
    let mut w = 0;
    let mut a = 0;
    for x in plan {
        match x {
            Act::U => u += 1,
            Act::W => w += 1,
            Act::C => c += 1,
            Act::A => a += 1,
        }
    }
    ActionCounts {
        unprotected: u,
        condom: c,
        withdrawal: w,
        abstain: a,
    }
}
