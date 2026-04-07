//! Wilcox kernel, age fecundability multiplier, ovulation mixture. README ~605–744.

use crate::types::UserOptions;

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
    if (19..=26).contains(&age) {
        1.00
    } else if (27..=29).contains(&age) {
        0.86
    } else if (30..=34).contains(&age) {
        0.77
    } else if (35..=37).contains(&age) {
        0.63
    } else if (38..=40).contains(&age) {
        0.49
    } else if (41..=44).contains(&age) {
        0.28
    } else if age >= 45 {
        0.10
    } else {
        1.00
    }
}

pub fn ovulation_weights(
    cycle_length_days: i32,
    sd_days: f64,
    opts: &UserOptions,
) -> Vec<(i32, f64)> {
    let center = f64::from(cycle_length_days - 14);
    let hw = opts.ovulation_window_half_width;
    let min_day = (1).max((center - f64::from(hw)) as i32);
    let max_day = cycle_length_days.min((center + f64::from(hw)) as i32);
    let mut raw: Vec<(i32, f64)> = Vec::new();
    for day in min_day..=max_day {
        raw.push((day, gaussian_weight(f64::from(day), center, sd_days)));
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
    opts: &UserOptions,
) -> Vec<f64> {
    let acts_per_day = acts_per_week / 7.0;
    let ovu = ovulation_weights(cycle_length_days, sd_days, opts);
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
