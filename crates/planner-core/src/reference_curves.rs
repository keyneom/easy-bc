//! Smoothed population reference curves (Apple WH Study, TREMIN, NSFG-style anchors).
//!
//! Source studies report age bands, but age effects should not jump at exact
//! birthdays. These helpers interpolate through representative anchor points so
//! lifecycle scaling moves gradually year to year.

use crate::types::UserOptions;

fn interpolate_age_anchor(age: i32, anchors: &[(f64, f64)]) -> f64 {
    let age = f64::from(age);
    if age <= anchors[0].0 {
        return anchors[0].1;
    }
    for pair in anchors.windows(2) {
        let (a0, v0) = pair[0];
        let (a1, v1) = pair[1];
        if age <= a1 {
            let t = ((age - a0) / (a1 - a0)).clamp(0.0, 1.0);
            return v0 + t * (v1 - v0);
        }
    }
    anchors.last().unwrap().1
}

pub fn reference_cycle_length_for_age(age: i32) -> f64 {
    interpolate_age_anchor(
        age,
        &[
            (18.0, 30.0),
            (24.0, 29.0),
            (29.0, 28.5),
            (34.0, 28.0),
            (40.0, 27.8),
            (44.0, 28.0),
            (48.0, 34.0),
            (50.0, 40.0),
            (55.0, 55.0),
        ],
    )
}

pub fn reference_cycle_sd_for_age(age: i32) -> f64 {
    interpolate_age_anchor(
        age,
        &[
            (18.0, 5.0),
            (24.0, 4.0),
            (29.0, 3.5),
            (34.0, 3.2),
            (39.0, 3.0),
            (42.0, 3.5),
            (45.0, 4.5),
            (48.0, 7.0),
            (50.0, 10.0),
            (55.0, 15.0),
        ],
    )
}

pub fn reference_frequency_for_age(age: i32) -> f64 {
    interpolate_age_anchor(
        age,
        &[
            (24.0, 4.5),
            (29.0, 4.0),
            (34.0, 3.5),
            (39.0, 3.0),
            (44.0, 2.5),
            (49.0, 2.0),
            (55.0, 1.5),
        ],
    )
}

pub fn scaled_cycle_length_for_age(age: i32, opts: &UserOptions) -> i32 {
    if opts.hold_lifecycle_constant {
        return opts.cycle_length_days;
    }
    let ref_baseline = reference_cycle_length_for_age(opts.age_years);
    let ref_at_age = reference_cycle_length_for_age(age);
    let scaled = f64::from(opts.cycle_length_days) * (ref_at_age / ref_baseline);
    scaled.round().clamp(21.0, 60.0) as i32
}

pub fn scaled_cycle_sd_for_age(age: i32, opts: &UserOptions) -> f64 {
    if opts.hold_lifecycle_constant {
        return opts.ovulation_sd_days;
    }
    let ref_baseline = reference_cycle_sd_for_age(opts.age_years);
    let ref_at_age = reference_cycle_sd_for_age(age);
    opts.ovulation_sd_days * (ref_at_age / ref_baseline)
}

pub fn scaled_frequency_for_age(age: i32, opts: &UserOptions) -> f64 {
    if opts.hold_lifecycle_constant {
        return opts.acts_per_week;
    }
    let ref_baseline = reference_frequency_for_age(opts.age_years);
    let ref_at_age = reference_frequency_for_age(age);
    opts.acts_per_week * (ref_at_age / ref_baseline)
}

#[cfg(test)]
mod tests {
    use super::{
        reference_cycle_length_for_age, reference_cycle_sd_for_age, reference_frequency_for_age,
    };

    #[test]
    fn lifecycle_reference_curves_move_smoothly_year_to_year() {
        for age in 18..55 {
            let cycle_delta = (reference_cycle_length_for_age(age + 1)
                - reference_cycle_length_for_age(age))
            .abs();
            assert!(
                cycle_delta <= 3.1,
                "cycle length cliff at {age}: {cycle_delta}"
            );

            let sd_delta =
                (reference_cycle_sd_for_age(age + 1) - reference_cycle_sd_for_age(age)).abs();
            assert!(sd_delta <= 1.6, "cycle SD cliff at {age}: {sd_delta}");

            let frequency_delta =
                (reference_frequency_for_age(age + 1) - reference_frequency_for_age(age)).abs();
            assert!(
                frequency_delta <= 0.2,
                "frequency cliff at {age}: {frequency_delta}"
            );
        }
    }

    #[test]
    fn cycle_length_reference_no_longer_dips_then_rebounds_in_late_thirties() {
        let ages = 34..=44;
        let lengths: Vec<i32> = ages
            .map(|age| reference_cycle_length_for_age(age).round() as i32)
            .collect();
        for window in lengths.windows(3) {
            assert!(
                !(window[0] > window[1] && window[2] > window[1]),
                "rounded cycle length should not make a visible dip-and-rebound: {:?}",
                window
            );
        }
    }
}
