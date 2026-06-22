//! Wilcox kernel, age fecundability multiplier, ovulation mixture. README ~605–744.

use crate::types::{BodySignalInputs, UserOptions};

#[derive(Debug, Clone, Copy)]
pub struct OvulationPosterior {
    pub mean_day: f64,
    pub sd_days: f64,
}

#[inline]
fn gaussian_weight(x: f64, mean: f64, sd: f64) -> f64 {
    let z = (x - mean) / sd;
    (-0.5 * z * z).exp()
}

/// Per-act conception probability by day relative to ovulation (Wilcox et al.).
pub fn fertile_kernel(rel: i32) -> f64 {
    match rel {
        -5 => 0.10,
        -4 => 0.16,
        -3 => 0.14,
        -2 => 0.27,
        -1 => 0.31,
        0 => 0.33,
        1 => 0.08,
        _ => 0.0,
    }
}

pub fn age_multiplier(age: i32) -> f64 {
    const ANCHORS: &[(f64, f64)] = &[
        (18.0, 1.00),
        (26.0, 1.00),
        (29.0, 0.86),
        (34.0, 0.77),
        (37.0, 0.63),
        (40.0, 0.49),
        (44.0, 0.28),
        (50.0, 0.10),
    ];

    let age = f64::from(age);
    if age <= ANCHORS[0].0 {
        return ANCHORS[0].1;
    }
    for pair in ANCHORS.windows(2) {
        let (a0, m0) = pair[0];
        let (a1, m1) = pair[1];
        if age <= a1 {
            let t = ((age - a0) / (a1 - a0)).clamp(0.0, 1.0);
            return m0 + t * (m1 - m0);
        }
    }
    ANCHORS.last().unwrap().1
}

pub fn ovulation_posterior(
    cycle_length_days: i32,
    sd_days: f64,
    body_signals: Option<&BodySignalInputs>,
    _opts: &UserOptions,
) -> OvulationPosterior {
    let prior_mean = f64::from(cycle_length_days - 14);
    let prior_sd = sd_days.max(0.5);
    let Some(signals) = body_signals else {
        return OvulationPosterior {
            mean_day: prior_mean,
            sd_days: prior_sd,
        };
    };

    let mut cues: Vec<(f64, f64)> = Vec::new();
    if let Some(day) = signals.cervical_mucus_peak_day {
        cues.push((f64::from(day) + 0.5, 1.5));
    }
    if let Some(day) = signals.basal_body_temperature_shift_day {
        cues.push((f64::from(day) - 1.0, 1.25));
    }
    if let Some(day) = signals.lh_surge_day {
        cues.push((f64::from(day) + 1.0, 1.0));
    }
    if let Some(day) = signals.wearable_temperature_shift_day {
        cues.push((f64::from(day) - 1.0, 1.5));
    }
    if cues.is_empty() {
        return OvulationPosterior {
            mean_day: prior_mean,
            sd_days: prior_sd,
        };
    }

    let prior_precision = 1.0 / (prior_sd * prior_sd);
    let mut precision_sum = prior_precision;
    let mut weighted_mean_sum = prior_mean * prior_precision;
    for (mean, cue_sd) in &cues {
        let precision = 1.0 / (cue_sd * cue_sd);
        precision_sum += precision;
        weighted_mean_sum += mean * precision;
    }

    let posterior_mean = weighted_mean_sum / precision_sum;
    let posterior_var = 1.0 / precision_sum;
    let mut conflict = 0.0;
    let mut cue_precision_sum = 0.0;
    for (mean, cue_sd) in &cues {
        let precision = 1.0 / (cue_sd * cue_sd);
        conflict += precision * (mean - posterior_mean).powi(2);
        cue_precision_sum += precision;
    }
    let conflict_var = if cue_precision_sum > 0.0 {
        conflict / cue_precision_sum
    } else {
        0.0
    };

    OvulationPosterior {
        mean_day: posterior_mean.clamp(1.0, f64::from(cycle_length_days)),
        sd_days: (posterior_var + conflict_var).sqrt().clamp(0.75, prior_sd),
    }
}

pub fn ovulation_weights(
    cycle_length_days: i32,
    sd_days: f64,
    body_signals: Option<&BodySignalInputs>,
    opts: &UserOptions,
) -> Vec<(i32, f64)> {
    let posterior = ovulation_posterior(cycle_length_days, sd_days, body_signals, opts);
    let center = posterior.mean_day;
    let effective_sd = posterior.sd_days;
    let hw = opts.ovulation_window_half_width;
    let min_day = (1).max((center - f64::from(hw)) as i32);
    let max_day = cycle_length_days.min((center + f64::from(hw)) as i32);
    let mut raw: Vec<(i32, f64)> = Vec::new();
    for day in min_day..=max_day {
        raw.push((day, gaussian_weight(f64::from(day), center, effective_sd)));
    }
    let total: f64 = raw.iter().map(|(_, w)| w).sum();
    if total <= 0.0 {
        return raw;
    }
    raw.into_iter().map(|(d, w)| (d, w / total)).collect()
}

pub fn raw_per_day_cycle_risk_for_age(
    age: i32,
    cycle_length_days: i32,
    acts_per_week: f64,
    sd_days: f64,
    body_signals: Option<&BodySignalInputs>,
    opts: &UserOptions,
) -> Vec<f64> {
    let acts_per_day = acts_per_week / 7.0;
    let ovu = ovulation_weights(cycle_length_days, sd_days, body_signals, opts);
    let age_mult = age_multiplier(age);
    let mut out = Vec::with_capacity(cycle_length_days as usize);
    for cycle_day in 1..=cycle_length_days {
        let mut p = 0.0;
        for (ovulation_day, weight) in &ovu {
            let rel = cycle_day - ovulation_day;
            p += weight * fertile_kernel(rel);
        }
        out.push(p * age_mult * acts_per_day);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::age_multiplier;

    #[test]
    fn age_multiplier_is_smooth_and_monotone() {
        let mut prev = age_multiplier(18);
        for age in 19..=55 {
            let next = age_multiplier(age);
            assert!(
                next <= prev + 1e-12,
                "age multiplier should not increase at age {age}: {next} > {prev}"
            );
            assert!(
                prev - next < 0.08,
                "age multiplier should not cliff-drop at age {age}: {prev} -> {next}"
            );
            prev = next;
        }
    }
}
