use thiserror::Error;

#[derive(Debug, Error)]
pub enum PlannerError {
    #[error("age_years must be between 15 and 55")]
    AgeOutOfRange,
    #[error("horizon_years must be between 1 and 40")]
    HorizonOutOfRange,
    #[error("cycle_length_days must be between 21 and 45")]
    CycleLengthOutOfRange,
    #[error("target_cumulative_failure must be >= 0 and <= 0.5")]
    TargetOutOfRange,
    #[error("condom_mode must be perfect, typical, or custom")]
    InvalidCondomMode,
    #[error("custom_condom_residual must be between 0 and 1")]
    CustomResidualOutOfRange,
    #[error("streak_aversion must be between 0 and 1")]
    StreakAversionOutOfRange,
    #[error("withdrawal_relative_risk must be between 0 and 1")]
    WithdrawalRelativeOutOfRange,
    #[error("withdrawal_typical_annual_failure must be >= 0 and <= 0.5")]
    WithdrawalAnnualFailureOutOfRange,
    #[error("combined_method_independence must be between 0 and 1")]
    CombinedMethodIndependenceOutOfRange,
    #[error("body signal day must fall within the cycle length")]
    BodySignalsOutOfRange,
    #[error("day override: invalid year_index or day for horizon")]
    InvalidDayOverride,
    #[error("calendar_cycles invalid or horizon length out of range")]
    CalendarCyclesOutOfRange,
    #[error("realized_cumulative_risk must be >= 0 and <= target_cumulative_failure")]
    RealizedRiskOutOfRange,
}
