//! Public types for inputs and planner output (serde + optional uniFFI).

use serde::{Deserialize, Serialize};

const MAX_CALENDAR_CYCLES: usize = 800;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum CondomMode {
    Perfect,
    #[default]
    Typical,
    Custom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PersistentMethod {
    #[default]
    None,
    PillOrRing,
    Patch,
    Shot,
    Implant,
    HormonalIud,
    CopperIud,
    Vasectomy,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ProtectedDayMethod {
    None,
    #[default]
    ExternalCondom,
    InternalCondom,
    Diaphragm,
    Spermicide,
    VaginalPhModulator,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum WithdrawalMode {
    #[default]
    None,
    Typical,
    Custom,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "UPPERCASE")]
pub enum RecommendedAction {
    U,
    C,
    /// Withdrawal (coitus interruptus); modeled as fractional day risk vs unprotected.
    W,
    A,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BodySignalInputs {
    pub cervical_mucus_peak_day: Option<i32>,
    pub basal_body_temperature_shift_day: Option<i32>,
    pub lh_surge_day: Option<i32>,
    pub wearable_temperature_shift_day: Option<i32>,
}

/// One menstrual cycle in the planning horizon with explicit demographics (calendar-mode planning).
/// When `calendar_cycles` on [`UserOptions`] is non-empty, the optimizer uses this list instead of
/// one synthetic cycle per `(age_years + horizon_index)` year.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CycleInstance {
    pub cycle_length_days: i32,
    pub cycle_sd_days: f64,
    pub acts_per_week: f64,
    pub age_years: i32,
    #[serde(default)]
    pub body_signals: Option<BodySignalInputs>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UserOptions {
    #[serde(default = "default_age")]
    pub age_years: i32,
    #[serde(default = "default_horizon")]
    pub horizon_years: i32,
    #[serde(default = "default_target")]
    pub target_cumulative_failure: f64,
    #[serde(default = "default_cycle")]
    pub cycle_length_days: i32,
    #[serde(default = "default_acts")]
    pub acts_per_week: f64,
    #[serde(default)]
    pub persistent_method: PersistentMethod,
    #[serde(default)]
    pub protected_day_method: ProtectedDayMethod,
    #[serde(default)]
    pub condom_mode: CondomMode,
    #[serde(default = "default_custom_residual")]
    pub custom_condom_residual: f64,
    #[serde(default = "default_streak")]
    pub streak_aversion: f64,
    #[serde(default)]
    pub hold_lifecycle_constant: bool,
    #[serde(default = "default_cycles_per_year")]
    pub cycles_per_year: f64,
    #[serde(default = "default_sdm_anchor")]
    pub sdm_reference_annual_failure: f64,
    #[serde(default = "default_condom_perfect")]
    pub condom_perfect_annual_failure: f64,
    #[serde(default = "default_condom_typical")]
    pub condom_typical_annual_failure: f64,
    #[serde(default = "default_ov_sd")]
    pub ovulation_sd_days: f64,
    #[serde(default = "default_ov_half")]
    pub ovulation_window_half_width: i32,
    #[serde(default = "default_year_crowding")]
    pub year_crowding_penalty: f64,
    #[serde(default = "default_time_pref")]
    pub time_preference_rate: f64,
    #[serde(default)]
    pub withdrawal_mode: WithdrawalMode,
    #[serde(default = "default_withdrawal_typical_annual_failure")]
    pub withdrawal_typical_annual_failure: f64,
    /// Fraction of unprotected per-day pregnancy risk retained when using withdrawal when `withdrawal_mode` is `custom`.
    #[serde(default = "default_withdrawal_relative")]
    pub withdrawal_relative_risk: f64,
    #[serde(default)]
    pub use_withdrawal_backup_on_protected_days: bool,
    /// Fraction of the idealized independent-method benefit retained when combining a protected day method with withdrawal.
    #[serde(default = "default_combined_method_independence")]
    pub combined_method_independence: f64,
    /// When non-empty, ignore per-year `age_years + index` scaling for cycle shape; use each entry as one cycle.
    #[serde(default)]
    pub calendar_cycles: Option<Vec<CycleInstance>>,
    /// Optional body-signal observations for the current cycle. In legacy year mode these apply to year 0 only.
    #[serde(default)]
    pub body_signals: Option<BodySignalInputs>,
    /// Cumulative pregnancy risk already attributed to logged exposures (reduces remaining budget).
    #[serde(default)]
    pub realized_cumulative_risk: f64,
    /// Lock these days to the given action before optimizing (1-based `day`, 0-based `year_index` row).
    #[serde(default)]
    pub initial_action_locks: Vec<DayOverride>,
}

fn default_age() -> i32 {
    34
}
fn default_horizon() -> i32 {
    20
}
/// Default narrative: 5% cumulative pregnancy risk over the **full** horizon (not per year).
fn default_target() -> f64 {
    0.05
}
fn default_cycle() -> i32 {
    28
}
fn default_acts() -> f64 {
    3.0
}
fn default_custom_residual() -> f64 {
    0.08
}
fn default_streak() -> f64 {
    0.5
}
fn default_cycles_per_year() -> f64 {
    13.0
}
fn default_sdm_anchor() -> f64 {
    0.05
}
fn default_condom_perfect() -> f64 {
    0.02
}
fn default_condom_typical() -> f64 {
    0.13
}
fn default_ov_sd() -> f64 {
    3.0
}
fn default_ov_half() -> i32 {
    7
}
fn default_year_crowding() -> f64 {
    0.8
}
fn default_time_pref() -> f64 {
    0.03
}
fn default_withdrawal_typical_annual_failure() -> f64 {
    0.20
}
fn default_withdrawal_relative() -> f64 {
    0.35
}
fn default_combined_method_independence() -> f64 {
    0.35
}

impl Default for UserOptions {
    fn default() -> Self {
        Self {
            age_years: default_age(),
            horizon_years: default_horizon(),
            target_cumulative_failure: default_target(),
            cycle_length_days: default_cycle(),
            acts_per_week: default_acts(),
            persistent_method: PersistentMethod::None,
            protected_day_method: ProtectedDayMethod::ExternalCondom,
            condom_mode: CondomMode::Typical,
            custom_condom_residual: default_custom_residual(),
            streak_aversion: default_streak(),
            hold_lifecycle_constant: false,
            cycles_per_year: default_cycles_per_year(),
            sdm_reference_annual_failure: default_sdm_anchor(),
            condom_perfect_annual_failure: default_condom_perfect(),
            condom_typical_annual_failure: default_condom_typical(),
            ovulation_sd_days: default_ov_sd(),
            ovulation_window_half_width: default_ov_half(),
            year_crowding_penalty: default_year_crowding(),
            time_preference_rate: default_time_pref(),
            withdrawal_mode: WithdrawalMode::None,
            withdrawal_typical_annual_failure: default_withdrawal_typical_annual_failure(),
            withdrawal_relative_risk: default_withdrawal_relative(),
            use_withdrawal_backup_on_protected_days: false,
            combined_method_independence: default_combined_method_independence(),
            calendar_cycles: None,
            body_signals: None,
            realized_cumulative_risk: 0.0,
            initial_action_locks: Vec::new(),
        }
    }
}

impl UserOptions {
    pub fn validate(&self) -> Result<(), crate::PlannerError> {
        if !(15..=55).contains(&self.age_years) {
            return Err(crate::PlannerError::AgeOutOfRange);
        }

        if let Some(ref cc) = self.calendar_cycles {
            if !cc.is_empty() {
                if cc.len() > MAX_CALENDAR_CYCLES {
                    return Err(crate::PlannerError::CalendarCyclesOutOfRange);
                }
                for c in cc {
                    if !(21..=60).contains(&c.cycle_length_days) {
                        return Err(crate::PlannerError::CalendarCyclesOutOfRange);
                    }
                    if !(15..=55).contains(&c.age_years) {
                        return Err(crate::PlannerError::CalendarCyclesOutOfRange);
                    }
                    if c.cycle_sd_days <= 0.0 || c.cycle_sd_days > 20.0 {
                        return Err(crate::PlannerError::CalendarCyclesOutOfRange);
                    }
                    if !(0.0..=14.0).contains(&c.acts_per_week) {
                        return Err(crate::PlannerError::CalendarCyclesOutOfRange);
                    }
                    validate_body_signals(c.cycle_length_days, c.body_signals.as_ref())?;
                }
            }
        }

        let legacy_horizon = match &self.calendar_cycles {
            None => true,
            Some(v) => v.is_empty(),
        };
        if legacy_horizon {
            if !(1..=40).contains(&self.horizon_years) {
                return Err(crate::PlannerError::HorizonOutOfRange);
            }
            if !(21..=45).contains(&self.cycle_length_days) {
                return Err(crate::PlannerError::CycleLengthOutOfRange);
            }
        }

        if self.target_cumulative_failure < 0.0 || self.target_cumulative_failure > 0.5 {
            return Err(crate::PlannerError::TargetOutOfRange);
        }
        if self.realized_cumulative_risk < 0.0
            || self.realized_cumulative_risk > self.target_cumulative_failure + 1e-15
        {
            return Err(crate::PlannerError::RealizedRiskOutOfRange);
        }
        if !(0.0..=1.0).contains(&self.custom_condom_residual) {
            return Err(crate::PlannerError::CustomResidualOutOfRange);
        }
        if self.streak_aversion < 0.0 || self.streak_aversion > 1.0 {
            return Err(crate::PlannerError::StreakAversionOutOfRange);
        }
        if !(0.0..=1.0).contains(&self.withdrawal_relative_risk) {
            return Err(crate::PlannerError::WithdrawalRelativeOutOfRange);
        }
        if self.withdrawal_typical_annual_failure < 0.0
            || self.withdrawal_typical_annual_failure > 0.5
        {
            return Err(crate::PlannerError::WithdrawalAnnualFailureOutOfRange);
        }
        if !(0.0..=1.0).contains(&self.combined_method_independence) {
            return Err(crate::PlannerError::CombinedMethodIndependenceOutOfRange);
        }
        validate_body_signals(self.cycle_length_days, self.body_signals.as_ref())?;
        Ok(())
    }
}

fn validate_body_signals(
    cycle_length_days: i32,
    body_signals: Option<&BodySignalInputs>,
) -> Result<(), crate::PlannerError> {
    let Some(signals) = body_signals else {
        return Ok(());
    };
    for maybe_day in [
        signals.cervical_mucus_peak_day,
        signals.basal_body_temperature_shift_day,
        signals.lh_surge_day,
        signals.wearable_temperature_shift_day,
    ] {
        if let Some(day) = maybe_day {
            if day < 1 || day > cycle_length_days {
                return Err(crate::PlannerError::BodySignalsOutOfRange);
            }
        }
    }
    Ok(())
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DerivedUxWeights {
    pub condom_cost: f64,
    pub abstain_cost: f64,
    pub streak_penalty: f64,
    pub soft_max_abstain_streak: i32,
    pub soft_max_streak_penalty: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SdmValidation {
    pub simulated_annual_risk: f64,
    pub sdm_published_anchor: f64,
    pub deviation_ratio: f64,
    pub within_tolerance: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CondomResidualsUsed {
    pub perfect: f64,
    pub typical: f64,
    pub custom: f64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MethodLibraryUsed {
    pub persistent_method: PersistentMethod,
    pub persistent_method_residual: f64,
    pub protected_day_method: ProtectedDayMethod,
    pub protected_day_method_residual: f64,
    pub withdrawal_mode: WithdrawalMode,
    pub withdrawal_residual: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub combined_protected_withdrawal_residual: Option<f64>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannerValidation {
    pub sdm_reference: SdmValidation,
    pub condom_residuals_used: CondomResidualsUsed,
    pub selected_condom_residual: f64,
    pub method_library: MethodLibraryUsed,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum PlannerWarning {
    TargetRequiresAbstinence {
        all_condom_cumulative_risk: f64,
        message: String,
    },
    HighCycleVariability {
        affected_years: Vec<HighVariabilityYear>,
        message: String,
    },
    HeavyAbstinenceBurden {
        message: String,
        /// Years where abstain days exceeded 50% of cycle length.
        affected_year_indices: Vec<i32>,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HighVariabilityYear {
    pub age: i32,
    pub cycle_sd_days: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionCounts {
    pub unprotected: i32,
    pub condom: i32,
    pub withdrawal: i32,
    pub abstain: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OverrideCost {
    #[serde(skip_serializing_if = "Option::is_none")]
    pub override_action: Option<String>,
    pub condoms: i32,
    pub abstinence_days: i32,
    /// Whether the override's added risk can be fully recovered within the
    /// same cycle. Disambiguates `(condoms == 0, abstinence_days == 0)`,
    /// which otherwise means either "recovered, no extra days needed" or
    /// "not recoverable" — clients need to know which to render the note.
    pub recovered: bool,
    /// Human-readable recovery note. Computed by the core but deliberately
    /// NOT serialized: it's a ~150-char sentence on every one of ~7300
    /// day-weights in a 20-year plan — pure JSON bloat that dominated
    /// client-side decode time. Fully derivable from `override_action` +
    /// `condoms` + `abstinence_days` + `recovered`, so clients reconstruct
    /// it on demand for the single day the user inspects. Kept on the
    /// struct (and populated) so Rust-side tests can assert on it directly.
    #[serde(skip_serializing, default)]
    pub note: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DayWeight {
    pub day: i32,
    pub recommended_action: RecommendedAction,
    pub raw_risk_score: i32,
    pub raw_risk_probability: f64,
    pub protected_risk_probability: f64,
    pub withdrawal_risk_probability: f64,
    pub recommended_risk_probability: f64,
    pub override_cost: OverrideCost,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SignalSummary {
    pub posterior_ovulation_mean_day: f64,
    pub posterior_ovulation_sd_days: f64,
    pub signals_used: BodySignalInputs,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct YearOutput {
    pub year_index: i32,
    pub age: i32,
    pub cycle_length_days: i32,
    pub cycle_sd_days: f64,
    pub effective_cycles_per_year: f64,
    pub literal_cycle: bool,
    pub acts_per_week: f64,
    pub cycle_risk: f64,
    pub annual_risk: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub signal_summary: Option<SignalSummary>,
    pub counts: ActionCounts,
    pub day_weights: Vec<DayWeight>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlannerResult {
    pub options_used: UserOptions,
    pub derived_ux_weights: DerivedUxWeights,
    pub validation: PlannerValidation,
    pub achieved_cumulative_risk: f64,
    pub target_met: bool,
    pub warnings: Vec<PlannerWarning>,
    pub years: Vec<YearOutput>,
}

/// Force a calendar day to a specific action when previewing a replan (1-based `day`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DayOverride {
    pub year_index: i32,
    pub day: i32,
    pub action: RecommendedAction,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PlanDayDiff {
    pub year_index: i32,
    pub day: i32,
    pub baseline_action: RecommendedAction,
    pub preview_action: RecommendedAction,
}

/// Baseline optimal plan vs plan after applying `overrides` and compensating upgrades on other days.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplanPreview {
    pub baseline: PlannerResult,
    pub preview: PlannerResult,
    /// Whether preview cumulative risk is at or below the target.
    pub preview_target_met: bool,
    /// Same as `preview_target_met` for now (cannot meet target without changing locked days).
    pub feasible: bool,
    pub message: Option<String>,
    /// All days where baseline and preview disagree (includes your override and algorithm compensations).
    pub diffs: Vec<PlanDayDiff>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ReplanPreviewRequest {
    pub options: UserOptions,
    pub overrides: Vec<DayOverride>,
}
