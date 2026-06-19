//! Personal-use fertility risk planner — calendar model and whole-horizon optimizer.
//! Algorithm ported from [README.md](../../README.md) JavaScript reference.

mod biology;
mod condoms;
mod error;
mod methods;
mod optimizer;
mod reference_curves;
pub mod types;

pub use error::PlannerError;
pub use optimizer::{effective_cumulative_target, fertility_risk_planner, replan_preview};
pub use types::{
    BodySignalInputs, CondomMode, CycleInstance, DayOverride, PersistentMethod, PlanDayDiff,
    PlannerResult, ProtectedDayMethod, RecommendedAction, ReplanPreview, ReplanPreviewRequest,
    UserOptions, WithdrawalMode,
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

#[cfg(feature = "ffi")]
uniffi::setup_scaffolding!();
