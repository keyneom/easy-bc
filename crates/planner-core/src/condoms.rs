//! Condom residual calibration vs reference population. README ~746–777.

use crate::biology::raw_per_day_cycle_risk_for_age;
use crate::types::UserOptions;

pub fn annual_risk_with_residual(day_risks: &[f64], residual: f64, cycles_per_year: f64) -> f64 {
    let mut cycle_surv = 1.0;
    for r in day_risks {
        cycle_surv *= 1.0 - r * residual;
    }
    // `cycle_surv` = P(no pregnancy in one cycle). Over `n` independent cycles per year:
    // P(at least one pregnancy in the year) = 1 - cycle_surv^n.
    // (The README JS used `1 - (1-cycleSurv)^n`, which is incorrect and broke calibration.)
    1.0 - cycle_surv.powf(cycles_per_year)
}

/// JS uses: if annualRiskWithResidual < target then lo = mid else hi = mid — finds residual where annual >= target at boundary.
pub fn solve_residual_for_annual_target(
    target_annual: f64,
    day_risks: &[f64],
    cycles_per_year: f64,
) -> f64 {
    let mut lo = 0.0_f64;
    let mut hi = 1.0_f64;
    for _ in 0..50 {
        let mid = (lo + hi) / 2.0;
        if annual_risk_with_residual(day_risks, mid, cycles_per_year) < target_annual {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    (lo + hi) / 2.0
}

pub fn reference_day_risks(opts: &UserOptions) -> Vec<f64> {
    raw_per_day_cycle_risk_for_age(28, 28, 2.0, 2.0, opts)
}

pub fn validate_against_sdm(opts: &UserOptions) -> crate::types::SdmValidation {
    let sdm_ref_risk = raw_per_day_cycle_risk_for_age(28, 28, 1.5, 2.0, opts);
    let mut cycle_surv = 1.0;
    for (i, r) in sdm_ref_risk.iter().enumerate() {
        let day = i as i32 + 1;
        let protected_by_sdm = (8..=19).contains(&day);
        if !protected_by_sdm {
            cycle_surv *= 1.0 - r;
        }
    }
    let cycle_risk = 1.0 - cycle_surv;
    let annual_risk = 1.0 - (1.0 - cycle_risk).powf(opts.cycles_per_year);
    let anchor = opts.sdm_reference_annual_failure;
    let deviation = if anchor.abs() > 1e-18 {
        ((annual_risk - anchor) / anchor).abs()
    } else {
        0.0
    };
    crate::types::SdmValidation {
        simulated_annual_risk: annual_risk,
        sdm_published_anchor: anchor,
        deviation_ratio: deviation,
        within_tolerance: deviation < 0.6,
    }
}
