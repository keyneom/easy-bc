//! Population reference curves (Apple WH Study, NSFG-style bands). README ~628–668.

use crate::types::UserOptions;

pub fn reference_cycle_length_for_age(age: i32) -> f64 {
    if age < 20 {
        30.0
    } else if age < 25 {
        29.0
    } else if age < 30 {
        28.5
    } else if age < 35 {
        28.0
    } else if age < 40 {
        27.5
    } else if age < 43 {
        27.0
    } else if age < 46 {
        28.0
    } else if age < 48 {
        32.0
    } else if age < 50 {
        40.0
    } else {
        55.0
    }
}

pub fn reference_cycle_sd_for_age(age: i32) -> f64 {
    if age < 20 {
        5.0
    } else if age < 25 {
        4.0
    } else if age < 30 {
        3.5
    } else if age < 35 {
        3.2
    } else if age < 40 {
        3.0
    } else if age < 43 {
        3.5
    } else if age < 46 {
        4.5
    } else if age < 48 {
        7.0
    } else if age < 50 {
        10.0
    } else {
        15.0
    }
}

pub fn reference_frequency_for_age(age: i32) -> f64 {
    if age < 25 {
        4.5
    } else if age < 30 {
        4.0
    } else if age < 35 {
        3.5
    } else if age < 40 {
        3.0
    } else if age < 45 {
        2.5
    } else if age < 50 {
        2.0
    } else {
        1.5
    }
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
