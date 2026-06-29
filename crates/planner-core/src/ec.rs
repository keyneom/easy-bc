//! Emergency-contraception (EC) effect model.
//!
//! Estimates how an EC dose changes the conception probability of a specific
//! at-risk act, by **integrating over the latent ovulation day** rather than
//! treating ovulation timing and conception probability as independent.
//!
//! For an act on cycle day `a` and an EC dose at cycle day `t`:
//!
//! ```text
//! P(pregnancy | EC)      Σ_o P(ov=o) · kernel(a − o) · P(EC fails | o, t, type)
//! ─────────────────  =  ────────────────────────────────────────────────────── = multiplier
//! P(pregnancy | none)            Σ_o P(ov=o) · kernel(a − o)
//! ```
//!
//! `kernel` is the same Wilcox per-act fertility kernel the planner uses, and
//! `P(ov=o)` is the cycle's ovulation posterior. The EC-failure term is computed
//! **per ovulation day** as a function of the *lead time* `o − t`, which is the
//! mechanistically correct dependency:
//!
//! - **Levonorgestrel (LNG)** prevents/delays ovulation only if taken before the
//!   LH surge, so it needs lead time before ovulation. No effect once ovulation
//!   has occurred. (FDA Plan B label; Noé et al. 2011.)
//! - **Ulipristal (UPA)** can postpone ovulation even after the surge has begun,
//!   so it works with less lead time and remains useful closer to ovulation.
//!   Generally more effective than LNG (ACOG).
//! - **Copper IUD** acts post-fertilization (spermicidal copper + blocks
//!   implantation), so it is effective essentially regardless of cycle timing
//!   within the 5-day window — the most effective EC option (ACOG; Cleland review).
//!
//! Because the dose timing enters only through `t = a + hours/24`, the
//! "sooner is better" behavior emerges from the lead-time integral instead of a
//! hand-tuned timeliness table. Three potency presets bracket parameter
//! uncertainty so callers can show an honest range, not a false point estimate.
//!
//! This is a planning estimate, not clinical efficacy. Body-weight/BMI effects
//! and drug interactions are not modeled. See docs/risk-accounting-and-ec.md.

use crate::biology::{fertile_kernel, OvulationPosterior};
use crate::types::EcType;

/// Estimated effect of one EC dose on the at-risk act and the cycle.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EcEstimate {
    /// Multiply the at-risk act's conception probability by this. `efficacy =
    /// 1 − multiplier`. Central estimate.
    pub conception_multiplier: f64,
    /// Optimistic bound (most efficacy / lowest residual risk).
    pub conception_multiplier_low: f64,
    /// Conservative bound (least efficacy / highest residual risk).
    pub conception_multiplier_high: f64,
    /// Expected forward shift of ovulation, in days (drives next-period timing).
    pub ovulation_delay_days: f64,
}

#[inline]
fn gaussian(x: f64, mean: f64, sd: f64) -> f64 {
    let s = sd.max(1e-6);
    let z = (x - mean) / s;
    (-0.5 * z * z).exp()
}

/// Parameter set bracketing EC efficacy uncertainty.
#[derive(Clone, Copy)]
struct Potency {
    /// Time-independent failure for a copper IUD within the window.
    copper_floor: f64,
    /// LNG residual failure with ample lead time, and the lead (days) needed
    /// to reach that floor.
    lng_floor: f64,
    lng_lead_full: f64,
    /// UPA residual failure with ample lead time, and lead needed for it.
    upa_floor: f64,
    upa_lead_full: f64,
}

impl Potency {
    /// Central estimate.
    fn central() -> Self {
        Self {
            copper_floor: 0.01,
            lng_floor: 0.18,
            lng_lead_full: 2.0,
            upa_floor: 0.12,
            upa_lead_full: 1.5,
        }
    }
    /// Optimistic (more efficacy): lower floors, works with less lead time.
    fn optimistic() -> Self {
        Self {
            copper_floor: 0.005,
            lng_floor: 0.10,
            lng_lead_full: 1.5,
            upa_floor: 0.06,
            upa_lead_full: 1.0,
        }
    }
    /// Conservative (less efficacy): higher floors, needs more lead time.
    fn conservative() -> Self {
        Self {
            copper_floor: 0.02,
            lng_floor: 0.30,
            lng_lead_full: 3.0,
            upa_floor: 0.22,
            upa_lead_full: 2.5,
        }
    }
}

/// Probability the EC *fails* to prevent conception given ovulation occurs on
/// day `o` and the dose was taken at cycle day `t`.
fn ec_failure_prob(ec_type: EcType, o: f64, t: f64, p: Potency) -> f64 {
    match ec_type {
        EcType::CopperIud => p.copper_floor,
        EcType::Levonorgestrel => {
            let lead = o - t;
            if lead <= 0.0 {
                1.0 // ovulation at/before the dose — LNG cannot stop it.
            } else {
                let frac = (lead / p.lng_lead_full).clamp(0.0, 1.0);
                1.0 - frac * (1.0 - p.lng_floor)
            }
        }
        EcType::Ulipristal => {
            let lead = o - t;
            // UPA can act slightly into the surge → tolerate a half-day past `t`.
            if lead <= -0.5 {
                1.0
            } else {
                let frac = ((lead + 0.5) / p.upa_lead_full).clamp(0.0, 1.0);
                1.0 - frac * (1.0 - p.upa_floor)
            }
        }
    }
}

fn ovulation_window(ovu: &OvulationPosterior) -> (f64, f64) {
    let lo = (ovu.mean_day - 4.0 * ovu.sd_days).floor().max(1.0);
    let hi = (ovu.mean_day + 4.0 * ovu.sd_days).ceil();
    (lo, hi)
}

/// Conception-survival multiplier under one potency preset: the fraction of the
/// act's conception risk that survives the EC, integrated over ovulation timing.
fn survival_multiplier(
    ec_type: EcType,
    act_cycle_day: f64,
    t: f64,
    ovu: &OvulationPosterior,
    p: Potency,
) -> f64 {
    let (lo, hi) = ovulation_window(ovu);
    let mut num = 0.0;
    let mut den = 0.0;
    let mut o = lo;
    while o <= hi {
        let k = fertile_kernel((act_cycle_day - o).round() as i32);
        if k > 0.0 {
            let conc = gaussian(o, ovu.mean_day, ovu.sd_days) * k;
            den += conc;
            num += conc * ec_failure_prob(ec_type, o, t, p);
        }
        o += 1.0;
    }
    if den > 0.0 {
        (num / den).clamp(0.0, 1.0)
    } else {
        // Act carries ~no conception risk for this posterior → EC is moot.
        1.0
    }
}

/// Expected forward ovulation shift: the share of ovulation mass still ahead of
/// the dose (and thus delayable), scaled by a method-specific maximum.
fn ovulation_delay(ec_type: EcType, t: f64, ovu: &OvulationPosterior, p: Potency) -> f64 {
    let max_delay = match ec_type {
        EcType::Levonorgestrel => 1.5,
        EcType::Ulipristal => 4.5,
        EcType::CopperIud => return 0.0,
    };
    let (lo, hi) = ovulation_window(ovu);
    let mut delayable = 0.0;
    let mut total = 0.0;
    let mut o = lo;
    while o <= hi {
        let w = gaussian(o, ovu.mean_day, ovu.sd_days);
        total += w;
        let lead = o - t;
        if lead > 0.0 {
            let reach = match ec_type {
                EcType::Levonorgestrel => (lead / p.lng_lead_full).clamp(0.0, 1.0),
                EcType::Ulipristal => ((lead + 0.5) / p.upa_lead_full).clamp(0.0, 1.0),
                EcType::CopperIud => 0.0,
            };
            delayable += w * reach;
        }
        o += 1.0;
    }
    if total > 0.0 {
        max_delay * (delayable / total)
    } else {
        0.0
    }
}

/// Estimate an EC dose's effect on an at-risk act on `act_cycle_day` (1-based;
/// may be fractional), given the cycle's `ovulation` posterior. `hours_from_act`
/// is the delay between the act and the dose; `None` assumes a typical ~24h.
pub fn ec_effect(
    ec_type: EcType,
    hours_from_act: Option<f64>,
    act_cycle_day: f64,
    ovulation: &OvulationPosterior,
) -> EcEstimate {
    let t = act_cycle_day + hours_from_act.unwrap_or(24.0) / 24.0;
    let central = survival_multiplier(ec_type, act_cycle_day, t, ovulation, Potency::central());
    let low = survival_multiplier(ec_type, act_cycle_day, t, ovulation, Potency::optimistic());
    let high = survival_multiplier(ec_type, act_cycle_day, t, ovulation, Potency::conservative());
    EcEstimate {
        conception_multiplier: central,
        conception_multiplier_low: low,
        conception_multiplier_high: high,
        ovulation_delay_days: ovulation_delay(ec_type, t, ovulation, Potency::central()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ovu(mean: f64, sd: f64) -> OvulationPosterior {
        OvulationPosterior {
            mean_day: mean,
            sd_days: sd,
        }
    }

    fn efficacy(e: EcEstimate) -> f64 {
        1.0 - e.conception_multiplier
    }

    #[test]
    fn reference_values_are_stable() {
        // Golden regression values for the EC model. Both clients run this exact
        // Rust code (web via wasm, Android via the uniFFI native library), so a
        // change here is a deliberate model change, not a port drift.
        let e = ec_effect(EcType::Levonorgestrel, Some(24.0), 11.0, &ovu(14.0, 2.0));
        assert!((e.conception_multiplier - 0.573405).abs() < 1e-5);
        let e = ec_effect(EcType::Ulipristal, Some(24.0), 12.0, &ovu(14.0, 1.5));
        assert!((e.conception_multiplier - 0.440675).abs() < 1e-5);
        assert!((e.ovulation_delay_days - 2.848420).abs() < 1e-5);
        let e = ec_effect(EcType::CopperIud, Some(12.0), 12.0, &ovu(14.0, 2.0));
        assert!((e.conception_multiplier - 0.010000).abs() < 1e-5);
    }

    #[test]
    fn bounds_bracket_the_central_estimate() {
        let e = ec_effect(EcType::Levonorgestrel, Some(12.0), 10.0, &ovu(14.0, 2.0));
        assert!(e.conception_multiplier_low <= e.conception_multiplier + 1e-9);
        assert!(e.conception_multiplier <= e.conception_multiplier_high + 1e-9);
    }

    #[test]
    fn copper_iud_is_near_total_regardless_of_dose_timing() {
        // Same fertile act, IUD placed promptly vs near the 5-day limit: copper
        // is insensitive to dose timing within the window.
        let prompt = ec_effect(EcType::CopperIud, Some(12.0), 12.0, &ovu(14.0, 2.0));
        let late = ec_effect(EcType::CopperIud, Some(110.0), 12.0, &ovu(14.0, 2.0));
        assert!(efficacy(prompt) > 0.97, "copper efficacy {}", efficacy(prompt));
        assert_eq!(prompt.conception_multiplier, late.conception_multiplier);
        assert_eq!(prompt.ovulation_delay_days, 0.0);
    }

    #[test]
    fn lng_helps_with_lead_time_and_not_after_ovulation() {
        // Act early in the fertile window, dose promptly, ovulation still days away.
        let early = ec_effect(EcType::Levonorgestrel, Some(12.0), 9.0, &ovu(14.0, 1.5));
        // Act after ovulation has passed, late dose — LNG can't help.
        let late = ec_effect(EcType::Levonorgestrel, Some(24.0), 16.0, &ovu(13.0, 1.5));
        assert!(
            efficacy(early) > 0.4,
            "early-window LNG efficacy should be substantial, got {}",
            efficacy(early)
        );
        assert!(
            efficacy(late) < 0.15,
            "post-ovulation LNG efficacy should be minimal, got {}",
            efficacy(late)
        );
        assert!(efficacy(early) > efficacy(late));
    }

    #[test]
    fn upa_at_least_as_effective_as_lng_near_ovulation() {
        let day = 12.0;
        let lng = ec_effect(EcType::Levonorgestrel, Some(24.0), day, &ovu(14.0, 1.5));
        let upa = ec_effect(EcType::Ulipristal, Some(24.0), day, &ovu(14.0, 1.5));
        assert!(
            efficacy(upa) >= efficacy(lng) - 1e-9,
            "UPA efficacy {} should be >= LNG {}",
            efficacy(upa),
            efficacy(lng)
        );
        assert!(upa.ovulation_delay_days > lng.ovulation_delay_days);
    }

    #[test]
    fn later_dose_reduces_efficacy() {
        let prompt = ec_effect(EcType::Levonorgestrel, Some(6.0), 10.0, &ovu(14.0, 2.0));
        let late = ec_effect(EcType::Levonorgestrel, Some(60.0), 10.0, &ovu(14.0, 2.0));
        assert!(
            efficacy(prompt) > efficacy(late),
            "prompt {} should beat late {}",
            efficacy(prompt),
            efficacy(late)
        );
    }

    #[test]
    fn lng_efficacy_lands_in_a_clinically_plausible_band() {
        // Representative: act on a fertile day, dose ~24h later, moderate timing
        // uncertainty. LNG real-world efficacy is broad (~50–85%); assert the
        // central estimate is in a defensible window, not a precise value.
        let e = ec_effect(EcType::Levonorgestrel, Some(24.0), 11.0, &ovu(14.0, 2.0));
        let eff = efficacy(e);
        assert!(
            (0.3..=0.9).contains(&eff),
            "LNG central efficacy {} outside plausible band",
            eff
        );
    }

    #[test]
    fn narrower_posterior_changes_the_estimate() {
        // The integral is joint, so sharpening ovulation timing must move the
        // result (it is not a separable pre-ovulation-mass multiplier).
        let wide = ec_effect(EcType::Levonorgestrel, Some(24.0), 11.0, &ovu(14.0, 3.0));
        let narrow = ec_effect(EcType::Levonorgestrel, Some(24.0), 11.0, &ovu(14.0, 1.0));
        assert!((wide.conception_multiplier - narrow.conception_multiplier).abs() > 1e-3);
    }
}
