//! Method library calibration for persistent, day-of-sex, and withdrawal methods.

use crate::condoms::solve_residual_for_annual_target;
use crate::types::{PersistentMethod, ProtectedDayMethod, UserOptions, WithdrawalMode};

const LEGACY_WITHDRAWAL_CUSTOM_DEFAULT: f64 = 0.35;

fn residual_from_annual_target(target_annual: f64, day_risks: &[f64], cycles_per_year: f64) -> f64 {
    if target_annual <= 0.0 {
        0.0
    } else {
        solve_residual_for_annual_target(target_annual, day_risks, cycles_per_year)
    }
}

pub fn persistent_method_residual(
    method: PersistentMethod,
    day_risks: &[f64],
    cycles_per_year: f64,
) -> f64 {
    let annual_failure = match method {
        PersistentMethod::None => return 1.0,
        PersistentMethod::PillOrRing => 0.07,
        PersistentMethod::Patch => 0.07,
        PersistentMethod::Shot => 0.04,
        PersistentMethod::Implant => 0.001,
        PersistentMethod::HormonalIud => 0.0025,
        PersistentMethod::CopperIud => 0.008,
        PersistentMethod::Vasectomy => 0.0015,
    };
    residual_from_annual_target(annual_failure, day_risks, cycles_per_year)
}

pub fn protected_day_method_residual(
    method: ProtectedDayMethod,
    external_condom_residual: f64,
    day_risks: &[f64],
    cycles_per_year: f64,
) -> f64 {
    match method {
        ProtectedDayMethod::None => 1.0,
        ProtectedDayMethod::ExternalCondom => external_condom_residual,
        ProtectedDayMethod::InternalCondom => {
            residual_from_annual_target(0.21, day_risks, cycles_per_year)
        }
        ProtectedDayMethod::Diaphragm => {
            residual_from_annual_target(0.17, day_risks, cycles_per_year)
        }
        ProtectedDayMethod::Spermicide | ProtectedDayMethod::VaginalPhModulator => {
            residual_from_annual_target(0.21, day_risks, cycles_per_year)
        }
    }
}

pub fn protected_day_method_enabled(method: ProtectedDayMethod) -> bool {
    !matches!(method, ProtectedDayMethod::None)
}

pub fn withdrawal_mode_enabled(mode: WithdrawalMode) -> bool {
    !matches!(mode, WithdrawalMode::None)
}

pub fn effective_withdrawal_mode(opts: &UserOptions) -> WithdrawalMode {
    match opts.withdrawal_mode {
        WithdrawalMode::None => WithdrawalMode::None,
        WithdrawalMode::Custom => WithdrawalMode::Custom,
        WithdrawalMode::Typical => {
            if (opts.withdrawal_relative_risk - LEGACY_WITHDRAWAL_CUSTOM_DEFAULT).abs() > 1e-12 {
                WithdrawalMode::Custom
            } else {
                WithdrawalMode::Typical
            }
        }
    }
}

pub fn withdrawal_residual(opts: &UserOptions, day_risks: &[f64], cycles_per_year: f64) -> f64 {
    match effective_withdrawal_mode(opts) {
        WithdrawalMode::None => 1.0,
        WithdrawalMode::Typical => residual_from_annual_target(
            opts.withdrawal_typical_annual_failure,
            day_risks,
            cycles_per_year,
        ),
        WithdrawalMode::Custom => opts.withdrawal_relative_risk,
    }
}

pub fn combined_protected_withdrawal_residual(
    protected_residual: f64,
    withdrawal_residual: f64,
    independence: f64,
) -> f64 {
    let ideal_independent = protected_residual * withdrawal_residual;
    let retained_improvement = (protected_residual - ideal_independent) * independence;
    (protected_residual - retained_improvement).clamp(0.0, protected_residual)
}
