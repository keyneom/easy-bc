//! Personal-use fertility risk planner — calendar model and whole-horizon optimizer.
//! Algorithm ported from [README.md](../../README.md) JavaScript reference.

mod biology;
mod condoms;
mod ec;
mod error;
mod methods;
mod optimizer;
mod reference_curves;
pub mod types;

use serde::{Deserialize, Serialize};

pub use biology::OvulationPosterior;
pub use ec::{ec_effect, EcEstimate};
pub use error::PlannerError;
pub use optimizer::{effective_cumulative_target, fertility_risk_planner, replan_preview};
pub use types::{
    BodySignalInputs, CondomMode, CycleInstance, DayOverride, EcType, PersistentMethod,
    PlanDayDiff, PlannerResult, PlannerWarning, ProtectedDayMethod, RecommendedAction,
    ReplanPreview, ReplanPreviewRequest, UserOptions, WithdrawalMode,
};

/// JSON API for FFI/WASM hosts.
pub fn plan_from_json(json: &str) -> Result<String, String> {
    let opts: UserOptions = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let out = fertility_risk_planner(opts).map_err(|e| e.to_string())?;
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

pub fn replan_preview_from_json(json: &str) -> Result<String, String> {
    let req: ReplanPreviewRequest = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let out = replan_preview(req.options, &req.overrides).map_err(|e| e.to_string())?;
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// Request for the EC-effect estimator. The caller supplies the cycle's
/// ovulation posterior (the planner returns it in `SignalSummary`, or a client
/// can pass the prior `cycle_length − 14` ± `sd`).
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EcEffectRequest {
    pub ec_type: EcType,
    #[serde(default)]
    pub hours_from_act: Option<f64>,
    /// 1-based cycle day of the at-risk act (may be fractional).
    pub act_cycle_day: f64,
    pub ovulation_mean_day: f64,
    pub ovulation_sd_days: f64,
}

/// Serializable mirror of [`EcEstimate`] for the JSON boundary.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EcEffectResponse {
    pub conception_multiplier: f64,
    pub conception_multiplier_low: f64,
    pub conception_multiplier_high: f64,
    pub ovulation_delay_days: f64,
}

/// JSON API: estimate an EC dose's effect on conception risk and ovulation timing.
pub fn ec_effect_from_json(json: &str) -> Result<String, String> {
    let req: EcEffectRequest = serde_json::from_str(json).map_err(|e| e.to_string())?;
    let posterior = OvulationPosterior {
        mean_day: req.ovulation_mean_day,
        sd_days: req.ovulation_sd_days,
    };
    let e = ec_effect(
        req.ec_type,
        req.hours_from_act,
        req.act_cycle_day,
        &posterior,
    );
    let resp = EcEffectResponse {
        conception_multiplier: e.conception_multiplier,
        conception_multiplier_low: e.conception_multiplier_low,
        conception_multiplier_high: e.conception_multiplier_high,
        ovulation_delay_days: e.ovulation_delay_days,
    };
    serde_json::to_string(&resp).map_err(|e| e.to_string())
}

#[cfg(feature = "wasm")]
use wasm_bindgen::prelude::*;

#[cfg(feature = "wasm")]
#[wasm_bindgen(js_name = planFertilityRiskJson)]
pub fn plan_fertility_risk_json_wasm(input: &str) -> Result<String, JsValue> {
    plan_from_json(input).map_err(|e| JsValue::from_str(&e))
}

#[cfg(feature = "wasm")]
#[wasm_bindgen(js_name = replanPreviewJson)]
pub fn replan_preview_json_wasm(input: &str) -> Result<String, JsValue> {
    replan_preview_from_json(input).map_err(|e| JsValue::from_str(&e))
}

#[cfg(feature = "wasm")]
#[wasm_bindgen(js_name = ecEffectJson)]
pub fn ec_effect_json_wasm(input: &str) -> Result<String, JsValue> {
    ec_effect_from_json(input).map_err(|e| JsValue::from_str(&e))
}

#[cfg(feature = "ffi")]
#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum FfiError {
    #[error("{msg}")]
    PlannerError { msg: String },
}

#[cfg(feature = "ffi")]
#[uniffi::export]
pub fn plan_fertility_risk_planner_json(opts_json: String) -> Result<String, FfiError> {
    plan_from_json(&opts_json).map_err(|msg| FfiError::PlannerError { msg })
}

#[cfg(feature = "ffi")]
#[uniffi::export]
pub fn replan_preview_json(request_json: String) -> Result<String, FfiError> {
    replan_preview_from_json(&request_json).map_err(|msg| FfiError::PlannerError { msg })
}

// Android calls this canonical implementation through the generated uniFFI
// binding and the release-built native library.
#[cfg(feature = "ffi")]
#[uniffi::export]
pub fn ec_effect_estimate_json(request_json: String) -> Result<String, FfiError> {
    ec_effect_from_json(&request_json).map_err(|msg| FfiError::PlannerError { msg })
}

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();
