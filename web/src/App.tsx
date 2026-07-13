import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  CalendarDays,
  ChartSpline,
  CheckCircle,
  Heart,
  History,
  Info,
  RefreshCw,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  User,
  Users,
} from "lucide-react";
import init, { planFertilityRiskJson, replanPreviewJson, ecEffectJson } from "../pkg/planner_core.js";
import {
  buildCalendarCycles,
  cycleLengthPosterior,
  currentCycleDayFromLastStart,
  inferCycleLengthsFromStarts,
  sampleStdDev,
  sdWidenFromVariance,
  type BodySignalInputs,
  type CalendarCycleRow,
} from "./periodTracker";
import {
  migrateLegacyPeriodStartsIfNeeded,
  idbGet,
  idbSet,
  KV_OPTIONS,
  KV_SESSION,
} from "./idbStore";
import {
  addPeriodStartDate,
  loadPeriodRecords,
  periodStartsFromRecords,
  removePeriodRecord,
  savePeriodRecords,
  setPeriodEnd,
  type PeriodRecord,
} from "./tracker/periodRecordsStore";
import { addDaysIso, daysBetweenInclusive } from "./tracker/calendarMath";
import { buildPlannerIcs, downloadIcsFile } from "./export/plannerToIcs";
import {
  resolvePlannerDayMeta,
  type PlannerWallPlan,
} from "./tracker/plannerWallMeta";
import {
  derivedBleedingEnd,
  estimateCyclePhase,
  isBleedingOnDate,
} from "./tracker/cyclePhase";
import {
  defaultPersistedSession,
  dayLogKey,
  daysSinceFirstCycleStart,
  estimateIncidentAdditionalRisk,
  hydratePersistedSession,
  incidentActionForType,
  initialLocksForPastDays,
  resolveHorizonRowAndDay,
  type DayLock,
  type CalendarDayLog,
  type IncidentType,
  type PersistedSession,
  type PlannerAction,
} from "./sessionUtils";
import { EC_COPY } from "./strings";
import { DayDetailPanel, type MethodRiskRow } from "./components/DayDetailPanel";
import { MonthCalendar, todayIsoLocal, type CalendarDensity } from "./components/MonthCalendar";
import { SyncSettings } from "./components/SyncSettings";
import { UpdateBanner } from "./components/UpdateBanner";
import { DeveloperLogPanel } from "./components/DeveloperLogPanel";
import {
  AutoSyncTriggerState,
  type AutoSyncReason,
} from "./sync/autoSyncState";
import { currentRpId, passkeysSupported } from "./sync/passkey";
import { formatLastSync } from "./sync/sessionSync";
import {
  buildSharedSyncPayload,
  canPublishRole,
  extractSharedPayload,
  findProfile,
  grantedParts,
  isEncryptedProfile,
  isLocalProfile,
  isSplitProfile,
  partIsWritable,
  restrictedParts,
  sharedPayloadFingerprint,
  sharedPayloadToSyncPayload,
  type SharedSyncState,
} from "./sync/sharedTypes";
import {
  DATASET_PART_LABELS,
  updateCalendarDayLog,
  type DatasetPart,
} from "./sync/datasets";
import { profileDisplayLabel } from "./sync/profileLabels";
import { shouldOpenSyncSettings } from "./sync/sharedRoute";
import {
  createLocalProfile,
  ensureProfileState,
  listPendingKeyResponses,
  loadSharedSyncState,
  renameManagedProfile,
  sharedSyncConfigFromEnv,
  switchManagedProfile,
  syncActiveDataset,
  updateManagedProfileAvatar,
  disposeRuntime,
} from "./sync/sharedSync";
import { profileKey } from "./sync/sharedFolderName";
import {
  ProfileChipSwitcher,
  type SwitcherProfileRow,
} from "./components/ProfileChipSwitcher";
import { updateProfileByKey } from "./sync/sharedRegistry";
import { avatarDataUrl, encodeAvatarFromFile } from "./ui/avatarEncode";
import { bindEasyBcSharingPoll } from "./sync/sharedSyncLifecycle";
import {
  plannerConfiguredFromPayload,
  portablePlannerOptions,
  type SyncPayloadV1,
} from "./sync/types";
import {
  computeInFlightRealizedRisk,
  type EcEffectFn,
  type InFlightDay,
} from "./tracker/realizedRisk";
import {
  chooseWebStorageMode,
  eraseEasyBcBrowserData,
  noteSensitiveWebSession,
  webStorageMode,
  type WebStorageMode,
} from "./privacy/webPrivacy";
import {
  EbAvatar,
  EbButton,
  EbGroupLabel,
  EbNavRow,
  EbPersonCard,
  EbProfileHeaderCard,
  EbStepDots,
  EbThemeModeToggle,
  type EbProfileBadge,
} from "./ui/Kit";

interface DayWeight {
  day: number;
  recommendedAction: PlannerAction;
  rawRiskScore: number;
  rawRiskProbability: number;
  protectedRiskProbability: number;
  withdrawalRiskProbability: number;
  recommendedRiskProbability: number;
  /** Single-act conception probability (per_act × age_mult), no frequency scaling. */
  perActConceptionProbability: number;
  overrideCost: {
    condoms: number;
    abstinenceDays: number;
    note: string;
  };
}

interface SignalSummary {
  posteriorOvulationMeanDay: number;
  posteriorOvulationSdDays: number;
  signalsUsed: BodySignalInputs;
}

interface YearOut {
  yearIndex: number;
  age: number;
  cycleLengthDays: number;
  cycleSdDays: number;
  effectiveCyclesPerYear: number;
  literalCycle: boolean;
  actsPerWeek: number;
  cycleRisk: number;
  annualRisk: number;
  signalSummary?: SignalSummary | null;
  dayWeights: DayWeight[];
}

type PersistentMethod =
  | "none"
  | "pill_or_ring"
  | "patch"
  | "shot"
  | "implant"
  | "hormonal_iud"
  | "copper_iud"
  | "vasectomy";
type ProtectedDayMethod =
  | "none"
  | "external_condom"
  | "internal_condom"
  | "diaphragm"
  | "spermicide"
  | "vaginal_ph_modulator";
type WithdrawalMode = "none" | "typical" | "custom";

interface MethodLibraryUsed {
  persistentMethod: PersistentMethod;
  persistentMethodResidual: number;
  protectedDayMethod: ProtectedDayMethod;
  protectedDayMethodResidual: number;
  withdrawalMode: WithdrawalMode;
  withdrawalResidual: number;
  combinedProtectedWithdrawalResidual?: number | null;
}

interface PlannerResult {
  achievedCumulativeRisk: number;
  targetMet: boolean;
  warnings?: Array<{ message: string }>;
  validation?: {
    methodLibrary?: MethodLibraryUsed;
  };
  optionsUsed: Record<string, unknown>;
  years: YearOut[];
}

interface PlanDayDiff {
  yearIndex: number;
  day: number;
  baselineAction: PlannerAction;
  previewAction: PlannerAction;
}

interface ReplanPreview {
  baseline: PlannerResult;
  preview: PlannerResult;
  previewTargetMet: boolean;
  feasible: boolean;
  message: string | null;
  diffs: PlanDayDiff[];
}

export type WasmOptions = {
  ageYears: number;
  horizonYears: number;
  targetCumulativeFailure: number;
  cycleLengthDays: number;
  actsPerWeek: number;
  persistentMethod: PersistentMethod;
  protectedDayMethod: ProtectedDayMethod;
  condomMode: "typical" | "perfect" | "custom";
  streakAversion: number;
  holdLifecycleConstant: boolean;
  realizedCumulativeRisk: number;
  withdrawalMode: WithdrawalMode;
  withdrawalTypicalAnnualFailure: number;
  withdrawalRelativeRisk: number;
  useWithdrawalBackupOnProtectedDays: boolean;
  combinedMethodIndependence: number;
  ovulationSdDays: number;
  bodySignals?: BodySignalInputs;
  calendarCycles?: CalendarCycleRow[];
  customCondomResidual?: number;
};

const defaultOptions = (): WasmOptions => ({
  ageYears: 34,
  horizonYears: 20,
  targetCumulativeFailure: 0.05,
  cycleLengthDays: 28,
  actsPerWeek: 3.5,
  persistentMethod: "none",
  protectedDayMethod: "none",
  condomMode: "perfect",
  streakAversion: 0.5,
  holdLifecycleConstant: false,
  realizedCumulativeRisk: 0,
  withdrawalMode: "none",
  withdrawalTypicalAnnualFailure: 0.2,
  withdrawalRelativeRisk: 0.35,
  useWithdrawalBackupOnProtectedDays: false,
  combinedMethodIndependence: 0.35,
  ovulationSdDays: 3.0,
});

function compactBodySignals(
  bodySignals?: BodySignalInputs | null,
): BodySignalInputs | undefined {
  if (!bodySignals) return undefined;
  const out: BodySignalInputs = {};
  if (bodySignals.cervicalMucusPeakDay != null) {
    out.cervicalMucusPeakDay = bodySignals.cervicalMucusPeakDay;
  }
  if (bodySignals.basalBodyTemperatureShiftDay != null) {
    out.basalBodyTemperatureShiftDay = bodySignals.basalBodyTemperatureShiftDay;
  }
  if (bodySignals.lhSurgeDay != null) {
    out.lhSurgeDay = bodySignals.lhSurgeDay;
  }
  if (bodySignals.wearableTemperatureShiftDay != null) {
    out.wearableTemperatureShiftDay = bodySignals.wearableTemperatureShiftDay;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function deriveCurrentCycleBodySignals(
  logs: PersistedSession["calendarDayLogs"],
  currentStart: string | undefined,
  fallback?: BodySignalInputs,
): BodySignalInputs | undefined {
  if (!currentStart) return compactBodySignals(fallback);
  const derived: BodySignalInputs = { ...(compactBodySignals(fallback) ?? {}) };
  const today = todayIsoLocal();
  for (const [iso, log] of Object.entries(logs)) {
    if (iso < currentStart || iso > today) continue;
    const cycleDay = daysSinceFirstCycleStart(
      currentStart,
      new Date(`${iso}T12:00:00`),
    ) + 1;
    if (log.mucus === "egg-white" || log.mucus === "eggwhite") {
      derived.cervicalMucusPeakDay = Math.max(
        derived.cervicalMucusPeakDay ?? 0,
        cycleDay,
      );
    }
    if (log.opk === "positive" || log.opk === "peak") {
      derived.lhSurgeDay = Math.max(derived.lhSurgeDay ?? 0, cycleDay);
    }
  }
  return compactBodySignals(derived);
}

function humanizeMethodLabel(value: string): string {
  return value.replaceAll("_", " ");
}

function fallbackMethodLibrary(opts: WasmOptions): MethodLibraryUsed {
  return {
    persistentMethod: opts.persistentMethod,
    persistentMethodResidual: Number.NaN,
    protectedDayMethod: opts.protectedDayMethod,
    protectedDayMethodResidual: Number.NaN,
    withdrawalMode: opts.withdrawalMode,
    withdrawalResidual: Number.NaN,
    combinedProtectedWithdrawalResidual: null,
  };
}

const ACTION_ORDER: PlannerAction[] = ["U", "W", "C", "A"];

const ACTION_LABELS: Record<PlannerAction, string> = {
  U: "Unprotected",
  W: "Withdrawal",
  C: "Protected",
  A: "Abstain",
};

function formatPercent(value: number): string {
  const pct = value * 100;
  if (pct < 0.01 && pct > 0) return "<0.01%";
  if (pct < 1) return `${pct.toFixed(2)}%`;
  return `${pct.toFixed(1)}%`;
}

function totalProjectedRisk(plan: PlannerResult, opts: WasmOptions): number {
  const realized = Math.max(0, Math.min(1, opts.realizedCumulativeRisk));
  const planned = Math.max(0, Math.min(1, plan.achievedCumulativeRisk));
  return Math.max(0, Math.min(1, 1 - (1 - realized) * (1 - planned)));
}

function actionCounts(dayWeights: DayWeight[]): Record<PlannerAction, number> {
  return dayWeights.reduce<Record<PlannerAction, number>>(
    (counts, day) => {
      counts[day.recommendedAction] += 1;
      return counts;
    },
    { U: 0, W: 0, C: 0, A: 0 },
  );
}

function PlannerRiskSummaryCard({
  plan,
  opts,
  calendarMode,
}: {
  plan: PlannerResult;
  opts: WasmOptions;
  calendarMode: boolean;
}) {
  const projectedRisk = totalProjectedRisk(plan, opts);
  const realized = Math.max(0, Math.min(1, opts.realizedCumulativeRisk));
  const target = opts.targetCumulativeFailure;
  const targetMet = projectedRisk <= target + 1e-9;
  // Visual scale: extend slightly past target so the over-target case is legible.
  const scaleMax = Math.max(target * 1.4, projectedRisk * 1.05, 0.001);
  const realizedFrac = Math.min(realized / scaleMax, 1);
  const projectedFrac = Math.min(projectedRisk / scaleMax, 1);
  const targetFrac = Math.min(target / scaleMax, 1);
  const sharePct = target > 0 ? Math.round((projectedRisk / target) * 100) : 0;

  return (
    <section className={`plan-risk-card ${targetMet ? "plan-risk-card-met" : "plan-risk-card-miss"}`}>
      <div className="plan-risk-heading">
        <span className="plan-risk-icon" aria-hidden>
          {targetMet ? <CheckCircle /> : <AlertTriangle />}
        </span>
        <div>
          <p className="eyebrow">{calendarMode ? "Cycle plan" : "Long-range plan"}</p>
          <h2>{targetMet ? "On track for target" : "Above target"}</h2>
        </div>
      </div>
      <div className="plan-risk-stats" aria-label="Plan risk summary">
        <div>
          <strong>{formatPercent(projectedRisk)}</strong>
          <span>Projected</span>
        </div>
        <div>
          <strong>{formatPercent(target)}</strong>
          <span>Target</span>
        </div>
        <div>
          <strong>{plan.years.length}</strong>
          <span>{calendarMode ? "cycles" : "yr"}</span>
        </div>
      </div>
      <div
        className="plan-projection-meter"
        role="img"
        aria-label={`Projected ${formatPercent(projectedRisk)} versus target ${formatPercent(target)}`}
      >
        <span
          className="plan-projection-realized"
          style={{ width: `${realizedFrac * 100}%` }}
          aria-hidden
        />
        <span
          className="plan-projection-projected"
          style={{ width: `${projectedFrac * 100}%` }}
          aria-hidden
        />
        <span
          className="plan-projection-target"
          style={{ left: `${targetFrac * 100}%` }}
          aria-hidden
        />
      </div>
      <p className="plan-budget-label">
        Projected is {sharePct}% of your target
        {realized > 0 ? ` · realized so far ${formatPercent(realized)}` : ""}
      </p>
    </section>
  );
}

function PlannerYearCard({ year, calendarMode }: { year: YearOut; calendarMode: boolean }) {
  const counts = actionCounts(year.dayWeights);
  const total = Math.max(1, year.cycleLengthDays);

  return (
    <article className="plan-year-card">
      <div className="plan-year-card-head">
        <div>
          <h3>{calendarMode ? `Cycle ${year.yearIndex + 1}` : `Age ${year.age}`}</h3>
          <p>{year.cycleLengthDays}-day cycle</p>
        </div>
        <div className="plan-year-risk">
          <strong>Annual: {formatPercent(year.annualRisk)}</strong>
          <span>Per-cycle: {formatPercent(year.cycleRisk)}</span>
        </div>
      </div>
      <div className="action-distribution" aria-label="Recommended action distribution">
        {ACTION_ORDER.map((action) => {
          const count = counts[action];
          if (count <= 0) return null;
          return (
            <span
              key={action}
              className={`action-distribution-segment action-${action}`}
              style={{ flexGrow: count / total }}
              title={`${ACTION_LABELS[action]}: ${count} days`}
            />
          );
        })}
      </div>
      <div className="plan-count-row">
        {ACTION_ORDER.map((action) => {
          const count = counts[action];
          if (count <= 0 && action === "W") return null;
          return (
            <span key={action} className={`plan-count-chip plan-count-${action}`}>
              <strong>{count}</strong>
              {action}
            </span>
          );
        })}
      </div>
      {year.signalSummary && (
        <p className="meta compact">
          Signal-adjusted ovulation: mean day{" "}
          <strong>{year.signalSummary.posteriorOvulationMeanDay.toFixed(1)}</strong>, SD{" "}
          <strong>{year.signalSummary.posteriorOvulationSdDays.toFixed(2)}</strong>.
        </p>
      )}
    </article>
  );
}

type ChoiceOption<T extends string> = { value: T; label: string };

function ChoiceChipGroup<T extends string>({
  label,
  description,
  value,
  options,
  onChange,
}: {
  label: string;
  description?: string;
  value: T;
  options: ChoiceOption<T>[];
  onChange: (value: T) => void;
}) {
  return (
    <div className="choice-chip-field">
      <div>
        <span className="choice-chip-label">{label}</span>
        {description && <p className="field-hint compact">{description}</p>}
      </div>
      <div className="choice-chip-row" role="group" aria-label={label}>
        {options.map((option) => (
          <button
            key={option.value}
            type="button"
            className="choice-chip"
            aria-pressed={value === option.value}
            onClick={() => onChange(option.value)}
          >
            {option.label}
          </button>
        ))}
      </div>
    </div>
  );
}

const PERSISTENT_METHOD_OPTIONS: ChoiceOption<PersistentMethod>[] = [
  { value: "none", label: "None" },
  { value: "pill_or_ring", label: "Pill/ring" },
  { value: "patch", label: "Patch" },
  { value: "shot", label: "Shot" },
  { value: "implant", label: "Implant" },
  { value: "hormonal_iud", label: "Hormonal IUD" },
  { value: "copper_iud", label: "Copper IUD" },
  { value: "vasectomy", label: "Vasectomy" },
];

const PROTECTED_METHOD_OPTIONS: ChoiceOption<ProtectedDayMethod>[] = [
  { value: "none", label: "None" },
  { value: "external_condom", label: "External condom" },
  { value: "internal_condom", label: "Internal condom" },
  { value: "diaphragm", label: "Diaphragm" },
  { value: "spermicide", label: "Spermicide" },
  { value: "vaginal_ph_modulator", label: "pH modulator" },
];

const CONDOM_MODE_OPTIONS: ChoiceOption<WasmOptions["condomMode"]>[] = [
  { value: "perfect", label: "Perfect" },
  { value: "typical", label: "Typical" },
  { value: "custom", label: "Custom" },
];

const WITHDRAWAL_MODE_OPTIONS: ChoiceOption<WithdrawalMode>[] = [
  { value: "none", label: "Not used" },
  { value: "typical", label: "Typical" },
  { value: "custom", label: "Custom" },
];

/** Settings hub views — parity with the Android settings routes. */
type SettingsView =
  | "hub"
  | "basics"
  | "protection"
  | "risk"
  | "sharing"
  | "profiles"
  | "setup"
  | "about";

function optionLabel<T extends string>(options: ChoiceOption<T>[], value: T): string {
  return options.find((option) => option.value === value)?.label ?? value;
}

function protectionSummary(opts: WasmOptions): string {
  const parts: string[] = [];
  if (opts.persistentMethod !== "none") {
    parts.push(optionLabel(PERSISTENT_METHOD_OPTIONS, opts.persistentMethod));
  }
  let protectedLabel = optionLabel(PROTECTED_METHOD_OPTIONS, opts.protectedDayMethod);
  if (opts.protectedDayMethod === "external_condom") {
    protectedLabel += ` (${opts.condomMode})`;
  }
  parts.push(protectedLabel);
  if (opts.withdrawalMode !== "none") parts.push("withdrawal");
  return parts.join(" + ");
}

type AnyProfileRecord = SharedSyncState["profiles"][number];

function settingsProfileMeta(state: SharedSyncState, record: AnyProfileRecord): string {
  if (isLocalProfile(record)) return "Local only · this device";
  if (record.ownerEmail.toLowerCase() !== state.ownerEmail.toLowerCase()) {
    return `Shared with you · ${record.role}`;
  }
  const participantCount = Object.keys(record.participantEmails ?? {}).length;
  return participantCount > 0
    ? `Shared · ${participantCount} ${participantCount === 1 ? "person" : "people"}`
    : "Private encrypted · your devices";
}

function settingsProfileBadge(state: SharedSyncState, record: AnyProfileRecord): EbProfileBadge {
  if (record.needsInitialLoad) return "waiting";
  if (isLocalProfile(record)) return "local";
  if (record.ownerEmail.toLowerCase() !== state.ownerEmail.toLowerCase()) {
    return canPublishRole(record.role) ? "shared" : "readonly";
  }
  return Object.keys(record.participantEmails ?? {}).length > 0 ? "shared" : "private";
}

function hasCalendarLogData(log: CalendarDayLog | undefined): boolean {
  if (!log) return false;
  if (log.actualAction && log.actualAction !== "NONE") return true;
  if (log.notes?.trim()) return true;
  if (log.mucus || log.opk || log.bbtCelsius != null) return true;
  if (log.mittelschmerz || log.breastTender || log.reconciled) return true;
  return Boolean(log.events?.length);
}

function riskForLoggedAction(day: DayWeight, action: CalendarDayLog["actualAction"]): number {
  switch (action) {
    case "U":
      return day.rawRiskProbability;
    case "W":
      return day.withdrawalRiskProbability;
    case "C":
      return day.protectedRiskProbability;
    case "A":
    case "NONE":
    case undefined:
      return 0;
  }
}

function fertileKernel(rel: number): number {
  switch (rel) {
    case -5:
      return 0.10;
    case -4:
      return 0.16;
    case -3:
      return 0.14;
    case -2:
      return 0.27;
    case -1:
      return 0.31;
    case 0:
      return 0.33;
    case 1:
      return 0.08;
    default:
      return 0;
  }
}

function ageMultiplier(age: number): number {
  const anchors: Array<[number, number]> = [
    [18, 1.00],
    [26, 1.00],
    [29, 0.86],
    [34, 0.77],
    [37, 0.63],
    [40, 0.49],
    [44, 0.28],
    [50, 0.10],
  ];
  if (age <= anchors[0][0]) return anchors[0][1];
  for (let index = 1; index < anchors.length; index += 1) {
    const [prevAge, prevValue] = anchors[index - 1];
    const [nextAge, nextValue] = anchors[index];
    if (age <= nextAge) {
      const t = Math.max(0, Math.min(1, (age - prevAge) / (nextAge - prevAge)));
      return prevValue + t * (nextValue - prevValue);
    }
  }
  return anchors[anchors.length - 1][1];
}

function methodResidual(action: PlannerAction, methodLibrary: MethodLibraryUsed | null): number {
  if (action === "A") return 0;
  if (action === "C") return finiteOr(methodLibrary?.protectedDayMethodResidual, 1);
  if (action === "W") return finiteOr(methodLibrary?.withdrawalResidual, 1);
  return 1;
}

function finiteOr(value: number | undefined | null, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

function buildMethodRiskRows(
  year: YearOut,
  dayWeight: DayWeight,
  methodLibrary: MethodLibraryUsed | null,
): MethodRiskRow[] {
  const persistent = finiteOr(methodLibrary?.persistentMethodResidual, 1);
  const mean = year.signalSummary?.posteriorOvulationMeanDay ?? Math.max(1, year.cycleLengthDays - 14);
  const sd = Math.max(0.5, year.signalSummary?.posteriorOvulationSdDays ?? year.cycleSdDays);
  const minOvulationDay = Math.max(1, Math.floor(mean - 2 * sd));
  const maxOvulationDay = Math.min(year.cycleLengthDays, Math.ceil(mean + 2 * sd));
  const perActBase = dayWeight.perActConceptionProbability * persistent;
  const peakBase = fertileKernel(0) * ageMultiplier(year.age) * persistent;
  let plausibleLowBase = Number.POSITIVE_INFINITY;
  let plausibleHighBase = 0;
  for (let ovulationDay = minOvulationDay; ovulationDay <= maxOvulationDay; ovulationDay += 1) {
    const value = fertileKernel(dayWeight.day - ovulationDay) * ageMultiplier(year.age) * persistent;
    plausibleLowBase = Math.min(plausibleLowBase, value);
    plausibleHighBase = Math.max(plausibleHighBase, value);
  }
  if (!Number.isFinite(plausibleLowBase)) plausibleLowBase = 0;

  const expectedDayPattern: Record<PlannerAction, number> = {
    U: dayWeight.rawRiskProbability,
    W: dayWeight.withdrawalRiskProbability,
    C: dayWeight.protectedRiskProbability,
    A: 0,
  };

  return ACTION_ORDER.map((action) => {
    const residual = methodResidual(action, methodLibrary);
    return {
      action,
      expectedDayPattern: expectedDayPattern[action],
      expectedSingleAct: perActBase * residual,
      plausibleLow: plausibleLowBase * residual,
      plausibleHigh: plausibleHighBase * residual,
      peakAligned: peakBase * residual,
    };
  });
}

type WebCycleLedger = {
  currentDayInCycle: number;
  cycleLengthDays: number;
  plannedCycleRisk: number;
  realizedSoFar: number;
  savedRiskVsBaseline: number;
  extraRiskVsBaseline: number;
  horizonRisk: number;
  horizonTarget: number;
  targetMet: boolean;
};

function CycleLedgerCard({ ledger }: { ledger: WebCycleLedger }) {
  const fraction =
    ledger.plannedCycleRisk > 0
      ? Math.min(1, ledger.realizedSoFar / ledger.plannedCycleRisk)
      : ledger.realizedSoFar > 0
        ? 1
        : 0;
  const overBudget = !ledger.targetMet;
  return (
    <section className="cycle-ledger-card">
      <div className="cycle-ledger-head">
        <span>Cycle risk ledger</span>
        <span>Day {ledger.currentDayInCycle} of {ledger.cycleLengthDays}</span>
      </div>
      <div className="cycle-ledger-meter" aria-hidden>
        <span
          className={overBudget ? "cycle-ledger-over" : undefined}
          style={{ width: `${fraction * 100}%` }}
        />
      </div>
      <div className="cycle-ledger-copy">
        <span>
          Logged {formatPercent(ledger.realizedSoFar)}; plan{" "}
          {formatPercent(ledger.plannedCycleRisk)}
        </span>
        {ledger.savedRiskVsBaseline > 1e-12 ? (
          <strong>Saved {formatPercent(ledger.savedRiskVsBaseline)} vs plan</strong>
        ) : ledger.extraRiskVsBaseline > 1e-12 ? (
          <strong className="danger-text">Spent {formatPercent(ledger.extraRiskVsBaseline)} vs plan</strong>
        ) : null}
      </div>
      {!ledger.targetMet && (
        <p className="danger-text compact">
          Horizon over target: {formatPercent(ledger.horizonRisk)} of{" "}
          {formatPercent(ledger.horizonTarget)}
        </p>
      )}
    </section>
  );
}

function optionsForWasm(
  o: WasmOptions,
  initialActionLocks?: DayLock[],
  bodySignalRowIndex = 0,
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...o };
  const bodySignals = compactBodySignals(o.bodySignals);
  if (bodySignals) out.bodySignals = bodySignals;
  else delete out.bodySignals;
  if (o.withdrawalMode !== "custom") {
    out.withdrawalRelativeRisk = 0.35;
  }
  if (o.calendarCycles?.length) {
    out.calendarCycles = o.calendarCycles.map((row, idx) => {
      const rowSignals = compactBodySignals(
        row.bodySignals ?? (idx === bodySignalRowIndex ? bodySignals : undefined),
      );
      if (rowSignals) return { ...row, bodySignals: rowSignals };
      const { bodySignals: _bodySignals, ...rest } = row;
      return rest;
    });
  } else {
    delete out.calendarCycles;
  }
  if (initialActionLocks?.length) {
    out.initialActionLocks = initialActionLocks.map((l) => ({
      yearIndex: l.yearIndex,
      day: l.day,
      action: l.action,
    }));
  }
  return out;
}

type AppTab = "tracker" | "planner" | "history" | "settings";

function initialAppTab(): AppTab {
  if (typeof window === "undefined") return "tracker";
  return shouldOpenSyncSettings(window.location.search) ? "settings" : "tracker";
}

export default function App() {
  const resultRef = useRef<HTMLElement | null>(null);
  const optionsFingerprintRef = useRef("");
  const autoSyncFingerprintRef = useRef("");
  const autoSyncTriggerRef = useRef(new AutoSyncTriggerState());
  const riskInputFingerprintRef = useRef("");
  const [wasmReady, setWasmReady] = useState(false);
  const [wasmError, setWasmError] = useState<string | null>(null);
  const [storageReady, setStorageReady] = useState(false);
  const [tab, setTab] = useState<AppTab>(initialAppTab);
  const initDate = useMemo(() => new Date(), []);
  const [viewYear, setViewYear] = useState(initDate.getFullYear());
  const [viewMonth, setViewMonth] = useState(initDate.getMonth());

  // Settings is a hub (parity with Android): deep links land on the sharing
  // panel (where SyncSettings processes them); everything else starts at hub.
  const [settingsView, setSettingsView] = useState<SettingsView>(() =>
    typeof window !== "undefined" && shouldOpenSyncSettings(window.location.search)
      ? "sharing"
      : "hub",
  );

  // The calendar is where today gets logged: returning to the tab always
  // re-centers on the current month so the user never edits a stale month.
  const selectTab = (next: AppTab) => {
    if (next === "tracker" && tab !== "tracker") {
      const now = new Date();
      setViewYear(now.getFullYear());
      setViewMonth(now.getMonth());
    }
    if (next === "settings" && tab !== "settings") {
      setSettingsView("hub");
    }
    setTab(next);
  };
  const [selectedDayIso, setSelectedDayIso] = useState<string | null>(null);
  const [periodRecords, setPeriodRecords] = useState<PeriodRecord[]>([]);

  const [opts, setOpts] = useState<WasmOptions>(defaultOptions);
  const [plan, setPlan] = useState<PlannerResult | null>(null);
  const [planError, setPlanError] = useState<string | null>(null);
  const [yearIdx, setYearIdx] = useState(0);
  const [modalDay, setModalDay] = useState<number | null>(null);
  const [preview, setPreview] = useState<ReplanPreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [locks, setLocks] = useState<DayLock[]>([]);
  const [session, setSession] = useState<PersistedSession>(defaultPersistedSession);
  const [applyPastLocks, setApplyPastLocks] = useState(true);
  const [incidentChoice, setIncidentChoice] = useState<IncidentType | "">("");
  const [incidentDay, setIncidentDay] = useState(1);
  const [calendarDensity, setCalendarDensity] = useState<CalendarDensity>("comfortable");
  const [planRegenerationPending, setPlanRegenerationPending] = useState(false);
  const [sharedSyncState, setSharedSyncState] = useState<SharedSyncState | null>(null);
  const [privacyPromptOpen, setPrivacyPromptOpen] = useState(false);
  const [storageMode, setStorageMode] = useState<WebStorageMode | null>(() =>
    typeof window === "undefined" ? null : webStorageMode(),
  );
  const [privacyEraseBusy, setPrivacyEraseBusy] = useState(false);
  const [autoSyncNotice, setAutoSyncNotice] = useState<{
    kind: "info" | "success" | "error";
    message: string;
  } | null>(null);
  const syncRpId = useMemo(currentRpId, []);
  const sharedSyncConfig = useMemo(() => sharedSyncConfigFromEnv(syncRpId), [syncRpId]);
  const syncReadOnly = useMemo(() => {
    if (!sharedSyncState) return false;
    const profile = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
    return profile ? profile.needsInitialLoad === true || !canPublishRole(profile.role) : false;
  }, [sharedSyncState]);

  useEffect(() => {
    if (!sharedSyncState?.profiles.some(isEncryptedProfile)) return;
    noteSensitiveWebSession();
    const mode = webStorageMode();
    setStorageMode(mode);
    if (!mode) setPrivacyPromptOpen(true);
  }, [sharedSyncState]);

  const selectStorageMode = (mode: WebStorageMode) => {
    chooseWebStorageMode(mode);
    setStorageMode(mode);
    setPrivacyPromptOpen(false);
  };

  const endPrivateBrowserSession = async () => {
    setPrivacyEraseBusy(true);
    disposeRuntime();
    try {
      await eraseEasyBcBrowserData({ preserveMode: true });
      window.location.replace(`${window.location.origin}${window.location.pathname}`);
    } catch (error) {
      setPrivacyEraseBusy(false);
      window.alert(error instanceof Error ? error.message : String(error));
    }
  };
  // Split shared profiles: which dataset parts this device was NOT granted,
  // and which granted parts are read-only. Drives the partial-access banner
  // and the day-panel editing gates.
  const activeDatasetAccess = useMemo(() => {
    if (!sharedSyncState) return null;
    const profile = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
    if (!profile || !isSplitProfile(profile)) return null;
    const missing = restrictedParts(profile);
    const granted = grantedParts(profile).map((part) => ({
      part,
      writable: partIsWritable(profile, part),
    }));
    return { missing, granted, partial: missing.length > 0 };
  }, [sharedSyncState]);
  const dayPanelRestricted = useMemo(() => {
    if (!activeDatasetAccess) return undefined;
    const blocked = (part: DatasetPart) =>
      activeDatasetAccess.missing.includes(part) ||
      activeDatasetAccess.granted.some((entry) => entry.part === part && !entry.writable);
    return {
      cycle: blocked("cycle"),
      intimacy: blocked("intimacy"),
      sensitive: blocked("sensitive"),
    };
  }, [activeDatasetAccess]);
  const activeEncryptedProfile = useMemo(() => {
    if (!sharedSyncState) return null;
    const profile = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
    return profile && isEncryptedProfile(profile) && profile.fileId ? profile : null;
  }, [sharedSyncState]);

  const sortedStarts = useMemo(() => periodStartsFromRecords(periodRecords), [periodRecords]);
  const sortedRecords = useMemo(
    () => [...periodRecords].sort((a, b) => a.start.localeCompare(b.start)),
    [periodRecords],
  );

  useEffect(() => {
    init()
      .then(() => setWasmReady(true))
      .catch((e: Error) => setWasmError(String(e)));
  }, []);

  useEffect(() => {
    void (async () => {
      await migrateLegacyPeriodStartsIfNeeded();
      const pr = await loadPeriodRecords();
      setPeriodRecords(pr);
      const raw = await idbGet<PersistedSession>(KV_SESSION);
      const savedOptions = await idbGet<Partial<WasmOptions>>(KV_OPTIONS);
      const savedSync = await loadSharedSyncState();
      const s = hydratePersistedSession(raw, pr.length);
      setSession(s);
      setLocks(s.locks);
      const loadedOptions: WasmOptions = {
        ...defaultOptions(),
        ...savedOptions,
        realizedCumulativeRisk: s.realizedCumulativeRisk,
      };
      optionsFingerprintRef.current = JSON.stringify(portablePlannerOptions(loadedOptions));
      setOpts(loadedOptions);
      const profileState =
        savedSync ??
        (await ensureProfileState(
          currentRpId(),
          buildSharedSyncPayload(loadedOptions, pr, s),
        ));
      setSharedSyncState(profileState);
      if (s.plannerConfigured) setPlanRegenerationPending(true);
      setStorageReady(true);
    })();
  }, []);

  useEffect(() => {
    if (!wasmReady || !storageReady) return;
    const h = window.setTimeout(() => {
      const payload: PersistedSession = {
        ...session,
        locks,
        realizedCumulativeRisk: opts.realizedCumulativeRisk,
      };
      void idbSet(KV_SESSION, payload);
    }, 400);
    return () => window.clearTimeout(h);
  }, [wasmReady, storageReady, session, locks, opts.realizedCumulativeRisk]);

  useEffect(() => {
    if (!storageReady) return;
    const fingerprint = JSON.stringify(portablePlannerOptions(opts));
    if (optionsFingerprintRef.current && optionsFingerprintRef.current !== fingerprint) {
      setSession((current) => ({
        ...current,
        plannerOptionsUpdatedAt: new Date().toISOString(),
      }));
    }
    optionsFingerprintRef.current = fingerprint;
    const h = window.setTimeout(() => void idbSet(KV_OPTIONS, opts), 400);
    return () => window.clearTimeout(h);
  }, [storageReady, opts]);

  const applySyncedPayload = useCallback(async (payload: SyncPayloadV1) => {
    const nextOptions: WasmOptions = {
      ...defaultOptions(),
      ...payload.planner.value,
      realizedCumulativeRisk: payload.planner.value.realizedCumulativeRisk,
    };
    const nextSession: PersistedSession = {
      ...session,
      locks: [],
      plannerConfigured: plannerConfiguredFromPayload(payload),
      calendarDayLogs: payload.calendarDayLogs,
      voluntaryAbstinenceDates: payload.voluntaryAbstinenceDates,
      voluntaryAbstinenceUpdatedAt: payload.voluntaryAbstinenceUpdatedAt,
      deletedPeriodStarts: payload.deletedPeriodStarts,
      deletedVoluntaryAbstinenceDates: payload.deletedVoluntaryAbstinenceDates,
      plannerOptionsUpdatedAt: payload.planner.updatedAt,
      ecJournalFlag: payload.ecJournal.value,
      ecJournalUpdatedAt: payload.ecJournal.updatedAt,
      realizedCumulativeRisk: payload.planner.value.realizedCumulativeRisk,
      androidPreferences: payload.androidPreferences ?? session.androidPreferences,
      ...(payload.profileMeta ? { profileMeta: payload.profileMeta } : {}),
    };
    optionsFingerprintRef.current = JSON.stringify(portablePlannerOptions(nextOptions));
    setLocks([]);
    setPeriodRecords(payload.periodRecords);
    setSession(nextSession);
    setOpts(nextOptions);
    setPlan(null);
    setPreview(null);
    if (nextSession.plannerConfigured) setPlanRegenerationPending(true);
    await Promise.all([
      savePeriodRecords(payload.periodRecords),
      idbSet(KV_SESSION, nextSession),
      idbSet(KV_OPTIONS, nextOptions),
    ]);
    if (payload.profileMeta && sharedSyncState) {
      const next = await updateProfileByKey(sharedSyncState, sharedSyncState.activeProfileKey, {
        avatarWebp: payload.profileMeta.avatarWebp,
        avatarUpdatedAt: payload.profileMeta.updatedAt,
      });
      setSharedSyncState(next);
    }
  }, [session, sharedSyncState]);

  const localSyncFingerprint = useMemo(
    () =>
      sharedPayloadFingerprint(
        buildSharedSyncPayload(
          opts,
          periodRecords,
          session,
          sharedSyncState
            ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
            : null,
        ),
      ),
    [opts, periodRecords, session, sharedSyncState],
  );

  const latestSyncInputsRef = useRef({
    options: opts,
    periodRecords,
    session,
    fingerprint: localSyncFingerprint,
  });

  useEffect(() => {
    latestSyncInputsRef.current = {
      options: opts,
      periodRecords,
      session,
      fingerprint: localSyncFingerprint,
    };
  }, [localSyncFingerprint, opts, periodRecords, session]);

  const markSyncComplete = useCallback((payload: SyncPayloadV1 | null) => {
    autoSyncFingerprintRef.current = payload
      ? sharedPayloadFingerprint(extractSharedPayload(payload))
      : "";
  }, []);

  // Global profile chip + switcher (docs/settings-profiles-redesign.md §1).
  // Runs the same publish-before-switch routine as the sharing screen so
  // switching from any tab is safe; the sheet stays open until confirmed.
  const [profileSwitchingKey, setProfileSwitchingKey] = useState<string | null>(null);
  const [profileSwitchNotice, setProfileSwitchNotice] = useState<string | null>(null);
  const [newProfileName, setNewProfileName] = useState("");
  // Onboarding wizard (docs/settings-profiles-redesign.md §7): 5 skippable
  // steps over the same live option setters the full sub-screens use.
  const [setupStep, setSetupStep] = useState(0);
  const [setupName, setSetupName] = useState("");
  const setupAvatarInputRef = useRef<HTMLInputElement>(null);
  // runPlan is defined further down (it needs the plan inputs); the wizard
  // only calls it from event handlers, so a ref bridges the ordering.
  const runPlanRef = useRef<(() => void) | null>(null);

  const finishSetup = useCallback(
    (destination: SettingsView = "hub") => {
      runPlanRef.current?.();
      setSetupStep(0);
      setSettingsView(destination);
    },
    [],
  );

  const applySetupName = useCallback(async () => {
    const name = setupName.trim();
    if (!name || !sharedSyncState) return;
    const active = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
    if (!active || profileDisplayLabel(sharedSyncState, active) === name) return;
    try {
      setSharedSyncState(await renameManagedProfile(sharedSyncState.activeProfileKey, name));
    } catch {
      // Naming is cosmetic; never block setup on it.
    }
  }, [setupName, sharedSyncState]);

  const applySetupAvatar = useCallback(
    async (file: File | undefined) => {
      if (!file || !sharedSyncState) return;
      try {
        const avatarWebp = await encodeAvatarFromFile(file);
        setSharedSyncState(
          await updateManagedProfileAvatar(sharedSyncState.activeProfileKey, avatarWebp),
        );
      } catch {
        // Photos are optional; initials remain.
      } finally {
        if (setupAvatarInputRef.current) setupAvatarInputRef.current.value = "";
      }
    },
    [sharedSyncState],
  );
  const switcherProfiles = useMemo<SwitcherProfileRow[]>(() => {
    if (!sharedSyncState) return [];
    return sharedSyncState.profiles.map((record) => {
      const key = profileKey(record.ownerEmail, record.datasetId);
      return {
        key,
        name: profileDisplayLabel(sharedSyncState, record),
        meta: settingsProfileMeta(sharedSyncState, record),
        badge: settingsProfileBadge(sharedSyncState, record),
        photoUrl: record.avatarWebp ? avatarDataUrl(record.avatarWebp) : undefined,
        active: key === sharedSyncState.activeProfileKey,
      };
    });
  }, [sharedSyncState]);

  const handleSwitchProfile = useCallback(
    async (key: string): Promise<boolean> => {
      if (!sharedSyncState || profileSwitchingKey) return false;
      setProfileSwitchingKey(key);
      setProfileSwitchNotice(null);
      try {
        const { options, periodRecords: records, session: currentSession } =
          latestSyncInputsRef.current;
        const local = buildSharedSyncPayload(
          options,
          records,
          currentSession,
          findProfile(sharedSyncState, sharedSyncState.activeProfileKey),
        );
        const result = await switchManagedProfile(sharedSyncConfig, key, local);
        const applied = sharedPayloadToSyncPayload(
          result.payload,
          currentSession.androidPreferences,
        );
        await applySyncedPayload(applied);
        setSharedSyncState(result.state);
        markSyncComplete(applied);
        return true;
      } catch (error) {
        setProfileSwitchNotice(
          error instanceof Error ? error.message : String(error),
        );
        return false;
      } finally {
        setProfileSwitchingKey(null);
      }
    },
    [
      applySyncedPayload,
      markSyncComplete,
      profileSwitchingKey,
      sharedSyncConfig,
      sharedSyncState,
    ],
  );

  const handleCreateLocalProfile = useCallback(
    async (displayName: string): Promise<boolean> => {
      if (!sharedSyncState || profileSwitchingKey) return false;
      setProfileSwitchingKey("__create__");
      setProfileSwitchNotice(null);
      try {
        const { options, periodRecords: records, session: currentSession } =
          latestSyncInputsRef.current;
        const local = buildSharedSyncPayload(
          options,
          records,
          currentSession,
          findProfile(sharedSyncState, sharedSyncState.activeProfileKey),
        );
        const result = await createLocalProfile(sharedSyncConfig, displayName, local);
        const applied = sharedPayloadToSyncPayload(
          result.payload,
          currentSession.androidPreferences,
        );
        await applySyncedPayload(applied);
        setSharedSyncState(result.state);
        markSyncComplete(applied);
        return true;
      } catch (error) {
        setProfileSwitchNotice(
          error instanceof Error ? error.message : String(error),
        );
        return false;
      } finally {
        setProfileSwitchingKey(null);
      }
    },
    [
      applySyncedPayload,
      markSyncComplete,
      profileSwitchingKey,
      sharedSyncConfig,
      sharedSyncState,
    ],
  );

  const runAutoSync = useCallback(
    async (reason: AutoSyncReason) => {
      if (!sharedSyncState || !sharedSyncConfig || !activeEncryptedProfile) return;
      if (syncReadOnly && reason === "change") return;
      if (!passkeysSupported()) {
        setAutoSyncNotice({
          kind: "error",
          message: "Encrypted sync is enabled, but this browser cannot use passkeys here.",
        });
        return;
      }
      if (!autoSyncTriggerRef.current.request(reason)) return;

      const { options, periodRecords: records, session: currentSession, fingerprint } =
        latestSyncInputsRef.current;
      autoSyncFingerprintRef.current = fingerprint;
      setAutoSyncNotice({
        kind: "info",
        message:
          reason === "change"
            ? "Merging encrypted changes…"
            : reason === "startup"
            ? "Checking encrypted sync…"
            : reason === "remote-change"
            ? "Applying remote encrypted changes…"
            : "Checking encrypted changes…",
      });

      try {
        const local = buildSharedSyncPayload(
          options,
          records,
          currentSession,
          sharedSyncState
            ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
            : null,
        );
        const result = await syncActiveDataset(sharedSyncConfig, local);
        autoSyncFingerprintRef.current = sharedPayloadFingerprint(result.payload);
        await applySyncedPayload(
          sharedPayloadToSyncPayload(result.payload, currentSession.androidPreferences),
        );
        const refreshed = await loadSharedSyncState();
        if (refreshed) setSharedSyncState(refreshed);
        setAutoSyncNotice({
          kind: "success",
          message: `Encrypted sync updated ${formatLastSync(result.syncedAt)}.`,
        });
        if (
          reason === "foreground" &&
          findProfile(refreshed ?? sharedSyncState, sharedSyncState.activeProfileKey)?.role ===
            "owner"
        ) {
          await listPendingKeyResponses(sharedSyncConfig);
        }
      } catch (error) {
        setAutoSyncNotice({
          kind: "error",
          message: `Encrypted sync needs attention: ${
            error instanceof Error ? error.message : String(error)
          }`,
        });
      } finally {
        if (autoSyncTriggerRef.current.finish()) {
          window.setTimeout(() => void runAutoSync("change"), 250);
        }
      }
    },
    [
      activeEncryptedProfile,
      applySyncedPayload,
      sharedSyncConfig,
      sharedSyncState,
      syncReadOnly,
    ],
  );

  useEffect(() => {
    if (sharedSyncState) return;
    autoSyncFingerprintRef.current = "";
    autoSyncTriggerRef.current.reset();
    setAutoSyncNotice(null);
  }, [sharedSyncState]);

  useEffect(() => {
    if (
      !storageReady ||
      !wasmReady ||
      !sharedSyncState ||
      !activeEncryptedProfile ||
      syncReadOnly
    ) return;
    if (autoSyncFingerprintRef.current === "") {
      autoSyncFingerprintRef.current = localSyncFingerprint;
      const h = window.setTimeout(() => void runAutoSync("startup"), 1_500);
      return () => window.clearTimeout(h);
    }
    if (autoSyncFingerprintRef.current === localSyncFingerprint) return;
    const h = window.setTimeout(() => void runAutoSync("change"), 1_800);
    return () => window.clearTimeout(h);
  }, [
    activeEncryptedProfile,
    localSyncFingerprint,
    runAutoSync,
    sharedSyncState,
    storageReady,
    syncReadOnly,
    wasmReady,
  ]);

  useEffect(() => {
    if (
      !storageReady ||
      !wasmReady ||
      !sharedSyncState ||
      !sharedSyncConfig ||
      !activeEncryptedProfile
    ) return;
    let cancelled = false;
    let pollController: { stop(): void } | null = null;

    void bindEasyBcSharingPoll({
      config: sharedSyncConfig,
      onEvents: async (events) => {
        if (cancelled) return;
        for (const event of events) {
          if (event.kind === "shared-dataset-changed") {
            void runAutoSync("remote-change");
            continue;
          }
          if (event.kind === "pending-key-response") {
            const profile = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
            if (profile?.role === "owner" || profile?.role === "admin") {
              setAutoSyncNotice({
                kind: "info",
                message: "Someone submitted an encrypted sync join request.",
              });
            }
            continue;
          }
          if (event.kind === "token-expiring-soon") {
            setAutoSyncNotice({
              kind: "info",
              message: "Google Drive access for encrypted sync expires soon. Open Settings to refresh.",
            });
            continue;
          }
          if (event.kind === "token-expired") {
            setAutoSyncNotice({
              kind: "error",
              message: "Google Drive access for encrypted sync expired. Open Settings to reconnect.",
            });
          }
        }
      },
    }).then((controller) => {
      if (cancelled) {
        controller?.stop();
        return;
      }
      pollController = controller;
    });

    return () => {
      cancelled = true;
      pollController?.stop();
    };
  }, [
    activeEncryptedProfile,
    runAutoSync,
    sharedSyncConfig,
    sharedSyncState,
    storageReady,
    wasmReady,
  ]);

  const runPlan = useCallback(() => {
    if (!wasmReady) return;
    setPlanError(null);
    setPreview(null);
    setLocks([]);
    setSession((s) => ({ ...s, plannerConfigured: true, locks: [] }));
    try {
      const lengths = opts.calendarCycles?.map((c) => c.cycleLengthDays) ?? [];
      const sorted = [...sortedStarts].sort();
      const firstStart = sorted[0];
      const currentStart = sorted.at(-1);
      const currentStartOffset = firstStart && currentStart
        ? daysSinceFirstCycleStart(firstStart, new Date(`${currentStart}T12:00:00`))
        : 0;
      const currentRow = resolveHorizonRowAndDay(lengths, currentStartOffset)?.row ?? 0;
      let initialLocks: DayLock[] | undefined;
      if (applyPastLocks && lengths.length > 0 && sorted.length > 0) {
        const first = sorted[0];
        const daysSince = daysSinceFirstCycleStart(first);
        const pos = resolveHorizonRowAndDay(lengths, daysSince);
        if (pos) {
          initialLocks = initialLocksForPastDays(lengths, pos, session.dayLogs);
        }
      }
      // Probe with no event budget first. The probe supplies the day-level
      // per-act risks needed to price explicit incidents without duplicating
      // the biological model in TypeScript.
      const currentBodySignals = deriveCurrentCycleBodySignals(
        session.calendarDayLogs,
        currentStart,
        opts.bodySignals,
      );
      const probeOptions = {
        ...opts,
        bodySignals: currentBodySignals,
        realizedCumulativeRisk: 0,
      };
      const probeJson = planFertilityRiskJson(
        JSON.stringify(optionsForWasm(probeOptions, initialLocks, currentRow)),
      );
      const probe = JSON.parse(probeJson) as PlannerResult;

      let realizedCumulativeRisk = 0;
      let finalLocks = initialLocks ? [...initialLocks] : [];
      const persistentResidual =
        probe.validation?.methodLibrary?.persistentMethodResidual;
      if (
        firstStart &&
        currentStart &&
        lengths.length > 0 &&
        Number.isFinite(persistentResidual)
      ) {
        const today = todayIsoLocal();
        const inFlightDays: InFlightDay[] = [];
        for (const [iso, log] of Object.entries(session.calendarDayLogs)) {
          if (iso < currentStart || iso > today || !(log.events?.length)) continue;
          const cycleDay =
            daysSinceFirstCycleStart(currentStart, new Date(`${iso}T12:00:00`)) + 1;
          const dayWeight = probe.years[currentRow]?.dayWeights[cycleDay - 1];
          if (!dayWeight) continue;
          inFlightDays.push({
            cycleDay,
            dayWeight,
            log,
          });
          const hasIncident = log.events.some(
            (event) => event.kind === "condom_broke" || event.kind === "unplanned_unprotected",
          );
          if (
            hasIncident &&
            !finalLocks.some((lock) => lock.yearIndex === currentRow && lock.day === cycleDay)
          ) {
            // The realized-risk increment replaces this exact probe risk. Keep
            // the incident day's probe action fixed in the final optimization
            // so the amount subtracted by the aggregator cannot disappear.
            finalLocks.push({
              yearIndex: currentRow,
              day: cycleDay,
              action: dayWeight.recommendedAction,
            });
          }
        }
        // EC estimator backed by the canonical Rust model (via wasm). Uses the
        // current cycle's ovulation posterior so Plan B timing is meaningful.
        const currentYear = probe.years[currentRow];
        const ovulationMeanDay =
          currentYear?.signalSummary?.posteriorOvulationMeanDay ??
          (currentYear ? currentYear.cycleLengthDays - 14 : 14);
        const ovulationSdDays =
          currentYear?.signalSummary?.posteriorOvulationSdDays ?? opts.ovulationSdDays;
        const ecEffect: EcEffectFn = (ecType, hoursFromAct, actCycleDay) => {
          try {
            const json = ecEffectJson(
              JSON.stringify({
                ecType,
                hoursFromAct,
                actCycleDay,
                ovulationMeanDay,
                ovulationSdDays,
              }),
            );
            const r = JSON.parse(json) as {
              conceptionMultiplier: number;
              conceptionMultiplierHigh: number;
              ovulationDelayDays: number;
            };
            return {
              // Budget against the least-effective modeled scenario. The
              // central and optimistic scenarios remain diagnostic outputs.
              conceptionMultiplier: r.conceptionMultiplierHigh,
              ovulationDelayDays: r.ovulationDelayDays,
            };
          } catch {
            return { conceptionMultiplier: 1, ovulationDelayDays: 0 };
          }
        };
        const aggregate = computeInFlightRealizedRisk(
          inFlightDays,
          { persistentMethodResidual: persistentResidual as number },
          ecEffect,
        );
        realizedCumulativeRisk = aggregate.realized;
      }

      const finalOptions = { ...probeOptions, realizedCumulativeRisk };
      const finalPlan = realizedCumulativeRisk > 0
        ? JSON.parse(
            planFertilityRiskJson(
              JSON.stringify(
                optionsForWasm(
                  finalOptions,
                  finalLocks.length > 0 ? finalLocks : undefined,
                  currentRow,
                ),
              ),
            ),
          ) as PlannerResult
        : probe;
      setOpts((current) =>
        Math.abs(current.realizedCumulativeRisk - realizedCumulativeRisk) < 1e-12
          ? current
          : { ...current, realizedCumulativeRisk }
      );
      setPlan(finalPlan);
      setYearIdx(0);
    } catch (e) {
      setPlanError(String(e));
      setPlan(null);
    }
  }, [
    wasmReady,
    opts,
    applyPastLocks,
    sortedStarts,
    session.dayLogs,
    session.calendarDayLogs,
  ]);
  runPlanRef.current = runPlan;

  const riskInputFingerprint = useMemo(
    () => JSON.stringify({
      periodStarts: sortedStarts,
      calendarInputs: Object.fromEntries(
        Object.entries(session.calendarDayLogs).map(([iso, log]) => [
          iso,
          { events: log.events, mucus: log.mucus, opk: log.opk },
        ]),
      ),
      calendarCycles: opts.calendarCycles,
      ageYears: opts.ageYears,
      target: opts.targetCumulativeFailure,
      persistentMethod: opts.persistentMethod,
      actsPerWeek: opts.actsPerWeek,
    }),
    [
      sortedStarts,
      session.calendarDayLogs,
      opts.calendarCycles,
      opts.ageYears,
      opts.targetCumulativeFailure,
      opts.persistentMethod,
      opts.actsPerWeek,
    ],
  );

  useEffect(() => {
    if (!storageReady) return;
    if (riskInputFingerprintRef.current === "") {
      riskInputFingerprintRef.current = riskInputFingerprint;
      return;
    }
    if (riskInputFingerprintRef.current === riskInputFingerprint) return;
    riskInputFingerprintRef.current = riskInputFingerprint;
    if (session.plannerConfigured) setPlanRegenerationPending(true);
  }, [riskInputFingerprint, session.plannerConfigured, storageReady]);

  useEffect(() => {
    if (!planRegenerationPending || !wasmReady || !storageReady) return;
    runPlan();
    setPlanRegenerationPending(false);
  }, [planRegenerationPending, runPlan, storageReady, wasmReady]);

  const mergeLock = useCallback((yearIndex: number, day: number, action: PlannerAction) => {
    setLocks((prev) => {
      const rest = prev.filter((l) => !(l.yearIndex === yearIndex && l.day === day));
      return [...rest, { yearIndex, day, action }];
    });
  }, []);

  const runPreviewAll = useCallback(() => {
    if (!wasmReady || locks.length === 0) return;
    setPreviewLoading(true);
    setPlanError(null);
    try {
      const body = JSON.stringify({
        options: optionsForWasm(opts),
        overrides: locks.map((l) => ({
          yearIndex: l.yearIndex,
          day: l.day,
          action: l.action,
        })),
      });
      const json = replanPreviewJson(body);
      setPreview(JSON.parse(json) as ReplanPreview);
    } catch (e) {
      setPlanError(String(e));
      setPreview(null);
    } finally {
      setPreviewLoading(false);
      setModalDay(null);
    }
  }, [wasmReady, opts, locks]);

  const applyPreview = useCallback(() => {
    if (!preview?.feasible) return;
    setPlan(preview.preview);
    setPreview(null);
    setLocks([]);
  }, [preview]);

  const addLockFromModal = useCallback(
    (day: number, action: PlannerAction) => {
      mergeLock(yearIdx, day, action);
      setModalDay(null);
    },
    [mergeLock, yearIdx],
  );

  const logAsLivedFromModal = useCallback(
    (day: number, action: PlannerAction) => {
      setSession((s) => ({
        ...s,
        dayLogs: { ...s.dayLogs, [dayLogKey(yearIdx, day)]: action },
      }));
      setModalDay(null);
    },
    [yearIdx],
  );

  const applyPredictedCycles = useCallback(
    (count: number) => {
      const sorted = [...sortedStarts].sort();
      const hist = inferCycleLengthsFromStarts(sorted);
      const calendarCycles = buildCalendarCycles(
        count,
        opts.ageYears,
        opts.actsPerWeek,
        opts.ovulationSdDays,
        hist,
      );
      setOpts((o) => ({
        ...o,
        calendarCycles,
        horizonYears: count,
      }));
    },
    [opts.ageYears, opts.actsPerWeek, opts.ovulationSdDays, sortedStarts],
  );

  const clearCalendarMode = useCallback(() => {
    setOpts((o) => {
      const { calendarCycles: _c, ...rest } = o;
      return { ...rest, horizonYears: 20 };
    });
  }, []);

  const updateBodySignal = useCallback(
    (key: keyof BodySignalInputs, value: string) => {
      setOpts((o) => {
        const nextSignals = { ...(o.bodySignals ?? {}) };
        if (value === "") delete nextSignals[key];
        else nextSignals[key] = Number(value);
        return {
          ...o,
          bodySignals: Object.keys(nextSignals).length > 0 ? nextSignals : undefined,
        };
      });
    },
    [],
  );

  const applyIncident = useCallback(() => {
    if (!incidentChoice || !plan?.years?.[yearIdx]) return;
    const action = incidentActionForType(incidentChoice);
    mergeLock(yearIdx, incidentDay, action);
    setSession((s) => ({
      ...s,
      dayLogs: {
        ...s.dayLogs,
        [dayLogKey(yearIdx, incidentDay)]: action,
      },
    }));
    setPreview(null);
    setIncidentChoice("");
  }, [incidentChoice, incidentDay, mergeLock, plan, yearIdx]);

  const plannerMetaForDate = useCallback(
    (iso: string) => {
      if (!plan?.years?.length || !opts.calendarCycles?.length || sortedStarts.length === 0) {
        return null;
      }
      const anchor = [...sortedStarts].sort()[0];
      const lengths = opts.calendarCycles.map((c) => c.cycleLengthDays);
      return resolvePlannerDayMeta(
        plan as PlannerWallPlan,
        lengths,
        anchor,
        iso,
      );
    },
    [plan, opts.calendarCycles, sortedStarts],
  );

  const exportPlannerIcs = useCallback(() => {
    if (!plan?.years?.length || !opts.calendarCycles?.length || sortedStarts.length === 0) return;
    const anchor = [...sortedStarts].sort()[0];
    const lengths = opts.calendarCycles.map((c) => c.cycleLengthDays);
    const body = buildPlannerIcs(
      plan as PlannerWallPlan,
      anchor,
      lengths,
      plan.years.length,
    );
    downloadIcsFile("easy-bc-planner.ics", body);
  }, [plan, opts.calendarCycles, sortedStarts]);

  const historyLengths = useMemo(
    () => inferCycleLengthsFromStarts(sortedStarts),
    [sortedStarts],
  );
  const lengthPosterior = useMemo(
    () => cycleLengthPosterior(historyLengths, opts.ageYears),
    [historyLengths, opts.ageYears],
  );
  const lengthSampleSd = historyLengths.length >= 2 ? sampleStdDev(historyLengths) : null;
  const varianceWidenExtra = sdWidenFromVariance(historyLengths);
  const effectiveRowSd = opts.calendarCycles?.[0]?.cycleSdDays ?? null;

  const goPrevMonth = useCallback(() => {
    setViewMonth((m) => {
      if (m === 0) {
        setViewYear((y) => y - 1);
        return 11;
      }
      return m - 1;
    });
  }, []);

  const goNextMonth = useCallback(() => {
    setViewMonth((m) => {
      if (m === 11) {
        setViewYear((y) => y + 1);
        return 0;
      }
      return m + 1;
    });
  }, []);

  const y = plan?.years[yearIdx];
  useEffect(() => {
    if (!y) {
      setIncidentDay(1);
      return;
    }
    setIncidentDay((prev) => Math.max(1, Math.min(prev, y.cycleLengthDays)));
  }, [y]);

  const lastStart = sortedStarts.length > 0 ? sortedStarts[sortedStarts.length - 1] : null;
  const cycleDayToday = lastStart ? currentCycleDayFromLastStart(lastStart) : null;

  const lengths = opts.calendarCycles?.map((c) => c.cycleLengthDays) ?? [];
  const horizonToday =
    sortedStarts.length > 0 && lengths.length > 0
      ? resolveHorizonRowAndDay(lengths, daysSinceFirstCycleStart(sortedStarts[0]))
      : null;

  const calendarMode = Boolean(opts.calendarCycles?.length);

  const selectedEstimate = selectedDayIso
    ? estimateCyclePhase(selectedDayIso, periodRecords, opts.ageYears)
    : null;

  const creditCount = Object.keys(session.voluntaryAbstinenceDates).length;
  const incidentDayWeight = y?.dayWeights.find((d) => d.day === incidentDay) ?? null;
  const incidentAddedRisk =
    incidentChoice && incidentDayWeight
      ? estimateIncidentAdditionalRisk(incidentDayWeight, incidentChoice, y?.actsPerWeek)
      : 0;
  const methodLibrary = plan ? (plan.validation?.methodLibrary ?? fallbackMethodLibrary(opts)) : null;
  const todayIso = todayIsoLocal();
  const activePlannerActions = useMemo<PlannerAction[]>(() => {
    if (!plan?.years?.length) return ["U", "C", "A"];
    const active = new Set<PlannerAction>();
    for (const year of plan.years) {
      for (const day of year.dayWeights) active.add(day.recommendedAction);
    }
    return ACTION_ORDER.filter((action) => active.has(action));
  }, [plan]);
  const openPeriodNudge = useMemo(() => {
    const open = [...sortedRecords].reverse().find((record) => record.end == null);
    if (!open) return null;
    const predictedEnd = derivedBleedingEnd(open, sortedRecords, todayIso);
    return todayIso > predictedEnd ? { start: open.start, predictedEnd } : null;
  }, [sortedRecords, todayIso]);
  const unreconciledCount = useMemo(() => {
    if (!calendarMode || !plan) return 0;
    let count = 0;
    for (let offset = 30; offset >= 1; offset -= 1) {
      const iso = addDaysIso(todayIso, -offset);
      const meta = plannerMetaForDate(iso);
      if (!meta || meta.recommendedAction === "A") continue;
      const log = session.calendarDayLogs[iso];
      if (!log?.reconciled && !log?.actualAction) count += 1;
    }
    return count;
  }, [calendarMode, plan, plannerMetaForDate, session.calendarDayLogs, todayIso]);
  const currentCycleLedger = useMemo<WebCycleLedger | null>(() => {
    if (!plan || !horizonToday || sortedStarts.length === 0 || lengths.length === 0) return null;
    const year = plan.years[horizonToday.row];
    if (!year) return null;
    const persistentResidual = methodLibrary?.persistentMethodResidual;
    const residual = Number.isFinite(persistentResidual) ? persistentResidual! : 1;
    const cycleStartOffset = lengths
      .slice(0, horizonToday.row)
      .reduce((sum, length) => sum + length, 0);
    const cycleStartIso = addDaysIso(sortedStarts[0], cycleStartOffset);
    let realizedSurvival = 1;
    let plannedSurvival = 1;

    for (let day = 1; day <= horizonToday.dayInCycle; day += 1) {
      const dayWeight = year.dayWeights[day - 1];
      if (!dayWeight) continue;
      const iso = addDaysIso(cycleStartIso, day - 1);
      const log = session.calendarDayLogs[iso];
      if (!hasCalendarLogData(log)) continue;
      const loggedRisk = riskForLoggedAction(dayWeight, log?.actualAction);
      const incidentRisk = (log?.events ?? [])
        .filter((event) => event.kind === "condom_broke" || event.kind === "unplanned_unprotected")
        .reduce(
          (sum) => sum + dayWeight.perActConceptionProbability * residual,
          0,
        );
      realizedSurvival *= 1 - Math.max(loggedRisk, Math.min(0.999, incidentRisk));
      plannedSurvival *= 1 - dayWeight.recommendedRiskProbability;
    }

    const realizedSoFar = Math.max(0, Math.min(1, 1 - realizedSurvival));
    const plannedForLoggedDays = Math.max(0, Math.min(1, 1 - plannedSurvival));
    const delta = realizedSoFar - plannedForLoggedDays;
    const horizonRisk = totalProjectedRisk(plan, opts);
    return {
      currentDayInCycle: horizonToday.dayInCycle,
      cycleLengthDays: lengths[horizonToday.row] ?? year.cycleLengthDays,
      plannedCycleRisk: year.cycleRisk,
      realizedSoFar,
      savedRiskVsBaseline: Math.max(0, -delta),
      extraRiskVsBaseline: Math.max(0, delta),
      horizonRisk,
      horizonTarget: opts.targetCumulativeFailure,
      targetMet: horizonRisk <= opts.targetCumulativeFailure + 1e-12,
    };
  }, [
    horizonToday,
    lengths,
    methodLibrary?.persistentMethodResidual,
    opts,
    plan,
    session.calendarDayLogs,
    sortedStarts,
  ]);
  const selectedRiskRows = useMemo<MethodRiskRow[] | undefined>(() => {
    if (!selectedDayIso || !plan) return undefined;
    const meta = plannerMetaForDate(selectedDayIso);
    if (!meta) return undefined;
    const year = plan.years[meta.row];
    const dayWeight = year?.dayWeights[meta.dayInCycle - 1];
    if (!year || !dayWeight) return undefined;
    return buildMethodRiskRows(year, dayWeight, methodLibrary);
  }, [methodLibrary, plan, plannerMetaForDate, selectedDayIso]);

  useEffect(() => {
    if (!plan || !resultRef.current) return;
    resultRef.current.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [plan]);

  return (
    <div className="app-shell">
      <header className="web-topbar">
        <div>
          <h1>EasyBC</h1>
          <p>Private planning, on this device</p>
        </div>
        {switcherProfiles.length > 0 ? (
          <ProfileChipSwitcher
            profiles={switcherProfiles}
            switchingKey={profileSwitchingKey}
            notice={profileSwitchNotice}
            onSwitch={handleSwitchProfile}
            onManageProfiles={() => {
              selectTab("settings");
              setSettingsView("profiles");
            }}
          />
        ) : (
          <div className="privacy-chip">
            <ShieldCheck size={16} aria-hidden />
            Local-first
          </div>
        )}
      </header>

      <UpdateBanner />

      <nav className="app-tabs" role="tablist" aria-label="Main sections">
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "tracker"}
          onClick={() => selectTab("tracker")}
        >
          <CalendarDays aria-hidden />
          Calendar
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "planner"}
          onClick={() => selectTab("planner")}
        >
          <ChartSpline aria-hidden />
          Plan
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "history"}
          onClick={() => selectTab("history")}
        >
          <History aria-hidden />
          History
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "settings"}
          onClick={() => selectTab("settings")}
        >
          <Settings aria-hidden />
          Settings
        </button>
      </nav>

      <main className="app-content">
      {!wasmReady && !wasmError && <p className="loading-state">Loading planner…</p>}
      {wasmError && <p className="warn">Planner failed to load: {wasmError}</p>}
      {sharedSyncState && syncReadOnly && (
        <p className="sync-readonly-banner" role="status">
          {findProfile(sharedSyncState, sharedSyncState.activeProfileKey)?.needsInitialLoad
            ? "This shared profile is waiting for its first remote load. Finish joining in Settings before editing."
            : "Viewing a shared encrypted profile in read-only mode. Switch to your profile in Settings to edit planner data, periods, or day logs."}
        </p>
      )}
      {sharedSyncState && !syncReadOnly && activeDatasetAccess?.partial && (
        <p className="sync-readonly-banner" role="status">
          This shared profile includes{" "}
          {activeDatasetAccess.granted
            .map(
              (entry) =>
                `${DATASET_PART_LABELS[entry.part]}${entry.writable ? "" : " (view only)"}`,
            )
            .join(", ")}
          . Sections that weren't shared with you stay hidden.
        </p>
      )}
      {sharedSyncState && autoSyncNotice && (
        <p className={`auto-sync-banner auto-sync-${autoSyncNotice.kind}`} role="status">
          <RefreshCw
            aria-hidden
            className={autoSyncNotice.kind === "info" ? "spin" : undefined}
          />
          <span>{autoSyncNotice.message}</span>
        </p>
      )}

      {wasmReady && (
        <>
          {tab === "tracker" && (
            <section className="tracker-shell">
              <div className="calendar-summary">
                <span>Tap any day to log bleeding or review its recommendation.</span>
                <span className="summary-stat"><strong>{creditCount}</strong> voluntary abstinence {creditCount === 1 ? "day" : "days"}</span>
              </div>
              {calendarMode && plan && sortedStarts.length > 0 && (
                <div className="export-ics-row">
                  <button type="button" className="ghost" onClick={exportPlannerIcs}>
                    Export planner (.ics)
                  </button>
                  <span className="field-hint">
                    Calendar-mode only: one all-day event per mapped date with action + raw risk. Not
                    medical advice.
                  </span>
                </div>
              )}
              {openPeriodNudge && (
                <button
                  type="button"
                  className="calendar-nudge calendar-nudge-period"
                  onClick={() => {
                    const today = new Date();
                    setViewYear(today.getFullYear());
                    setViewMonth(today.getMonth());
                    setSelectedDayIso(todayIso);
                  }}
                >
                  <span>Period still open past predicted end.</span>
                  <strong>Confirm today →</strong>
                </button>
              )}
              {unreconciledCount > 0 && (
                <div className="calendar-nudge calendar-nudge-reconcile">
                  <span>
                    Reconcile {unreconciledCount} past {unreconciledCount === 1 ? "day" : "days"}
                  </span>
                  <strong>Open days to log what happened</strong>
                </div>
              )}
              {cycleDayToday !== null && lastStart && (
                <p className="meta">
                  Last logged period <strong>start</strong>: {lastStart} — approx. cycle day{" "}
                  <strong>{cycleDayToday}</strong> from starts only (use Calendar for end dates).
                </p>
              )}
              <MonthCalendar
                year={viewYear}
                monthIndex={viewMonth}
                periodRecords={periodRecords}
                ageYears={opts.ageYears}
                todayIso={todayIso}
                selectedDayIso={selectedDayIso}
                voluntaryAbstinence={session.voluntaryAbstinenceDates}
                calendarDayLogs={session.calendarDayLogs}
                activeActions={activePlannerActions}
                plannerDayMeta={plannerMetaForDate}
                calendarDensity={calendarDensity}
                onCalendarDensityChange={setCalendarDensity}
                onSelectDay={(iso) => setSelectedDayIso(iso)}
                onPrevMonth={goPrevMonth}
                onNextMonth={goNextMonth}
                onToday={() => {
                  const today = new Date();
                  setViewYear(today.getFullYear());
                  setViewMonth(today.getMonth());
                }}
              />
              {currentCycleLedger && <CycleLedgerCard ledger={currentCycleLedger} />}
              <DayDetailPanel
                iso={selectedDayIso}
                onClose={() => setSelectedDayIso(null)}
                restricted={dayPanelRestricted}
                estimate={selectedEstimate}
                plannerMeta={selectedDayIso ? plannerMetaForDate(selectedDayIso) : null}
                isBleeding={
                  selectedDayIso
                    ? isBleedingOnDate(selectedDayIso, periodRecords)
                    : false
                }
                hasCredit={
                  selectedDayIso
                    ? Boolean(session.voluntaryAbstinenceDates[selectedDayIso])
                    : false
                }
                activeActions={activePlannerActions}
                onToggleCredit={() => {
                  if (!selectedDayIso) return;
                  setSession((s) => {
                    const v = { ...s.voluntaryAbstinenceDates };
                    const updated = { ...s.voluntaryAbstinenceUpdatedAt };
                    const deleted = { ...s.deletedVoluntaryAbstinenceDates };
                    const now = new Date().toISOString();
                    if (v[selectedDayIso]) {
                      delete v[selectedDayIso];
                      delete updated[selectedDayIso];
                      deleted[selectedDayIso] = now;
                    } else {
                      v[selectedDayIso] = true;
                      updated[selectedDayIso] = now;
                      delete deleted[selectedDayIso];
                    }
                    return {
                      ...s,
                      voluntaryAbstinenceDates: v,
                      voluntaryAbstinenceUpdatedAt: updated,
                      deletedVoluntaryAbstinenceDates: deleted,
                    };
                  });
                }}
                onMarkPeriodStart={() => {
                  if (!selectedDayIso) return;
                  void addPeriodStartDate(selectedDayIso).then((records) => {
                    setPeriodRecords(records);
                    setSession((current) => {
                      const deleted = { ...current.deletedPeriodStarts };
                      delete deleted[selectedDayIso];
                      return { ...current, deletedPeriodStarts: deleted };
                    });
                  });
                }}
                onMarkPeriodEnd={() => {
                  if (!selectedDayIso) return;
                  void setPeriodEnd(selectedDayIso).then(setPeriodRecords);
                }}
                calendarPlanActive={calendarMode && Boolean(plan)}
                dayLog={selectedDayIso ? session.calendarDayLogs[selectedDayIso] : undefined}
                riskRows={selectedRiskRows}
                onUpdateDayLog={(patch) => {
                  if (!selectedDayIso) return;
                  setSession((current) => ({
                    ...current,
                    calendarDayLogs: {
                      ...current.calendarDayLogs,
                      [selectedDayIso]: updateCalendarDayLog(
                        current.calendarDayLogs[selectedDayIso],
                        "actualAction" in patch
                          ? { ...patch, reconciled: patch.actualAction ? true : undefined }
                          : patch,
                        new Date().toISOString(),
                      ),
                    },
                  }));
                }}
              />
            </section>
          )}

          {tab === "history" && (
            <section>
              <h2>Period history</h2>
              <p className="hint">
                Each row is a bleeding episode. Set <strong>last bleeding day</strong> from the
                Calendar (or here via consistency) so the app can infer length. Removing a row deletes
                that period start.
              </p>
              {sortedRecords.length === 0 ? (
                <p className="meta">No periods yet — add a start from the Calendar tab.</p>
              ) : (
                <table className="history-table">
                  <thead>
                    <tr>
                      <th>Start</th>
                      <th>End (derived or set)</th>
                      <th>Bleeding days</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortedRecords.map((r) => {
                      const end = derivedBleedingEnd(r, sortedRecords);
                      const isOpen = r.end == null;
                      const len = daysBetweenInclusive(r.start, end);
                      return (
                        <tr key={r.start}>
                          <td>{r.start}</td>
                          <td>{isOpen ? `${end} (estimated)` : end}</td>
                          <td>{len}</td>
                          <td>
                            <button
                              type="button"
                              className="ghost"
                              onClick={() => {
                                const deletedAt = new Date().toISOString();
                                setSession((current) => ({
                                  ...current,
                                  deletedPeriodStarts: {
                                    ...current.deletedPeriodStarts,
                                    [r.start]: deletedAt,
                                  },
                                }));
                                void removePeriodRecord(r.start).then(setPeriodRecords);
                              }}
                            >
                              Remove
                            </button>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              )}
            </section>
          )}

          {tab === "settings" && settingsView === "hub" && (
            <section className="settings-screen">
              <div className="screen-heading">
                <p className="eyebrow">{session.plannerConfigured ? "Settings" : "Welcome"}</p>
                <h2>{session.plannerConfigured ? "Profile & planning" : "Set up your profile"}</h2>
                <p>
                  {session.plannerConfigured
                    ? "Update your inputs to regenerate your personalized cycle plan."
                    : "Configure your profile to get a personalized cycle plan."}
                  {" "}All calculations run on this device.
                </p>
              </div>
              {!session.plannerConfigured && (
                <EbButton
                  variant="primary"
                  onClick={() => {
                    setSetupStep(0);
                    setSettingsView("setup");
                  }}
                >
                  Start setup — five quick steps, all skippable
                </EbButton>
              )}
              {sharedSyncState && (() => {
                const active = findProfile(sharedSyncState, sharedSyncState.activeProfileKey);
                if (!active) return null;
                return (
                  <EbProfileHeaderCard
                    name={profileDisplayLabel(sharedSyncState, active)}
                    meta={settingsProfileMeta(sharedSyncState, active)}
                    colorKey={sharedSyncState.activeProfileKey}
                    photoUrl={active.avatarWebp ? avatarDataUrl(active.avatarWebp) : undefined}
                    badge={settingsProfileBadge(sharedSyncState, active)}
                    actionLabel="Switch"
                    onAction={() => setSettingsView("profiles")}
                  />
                );
              })()}
              <div className="appearance-row">
                <span className="appearance-label">Appearance</span>
                <EbThemeModeToggle />
              </div>
              <EbGroupLabel>Profile</EbGroupLabel>
              <EbNavRow
                icon={<User />}
                title="Plan basics"
                value={`Age ${opts.ageYears} · ${opts.cycleLengthDays}-day baseline cycle`}
                onClick={() => setSettingsView("basics")}
              />
              <EbNavRow
                icon={<Heart />}
                title="Protection"
                value={protectionSummary(opts)}
                onClick={() => setSettingsView("protection")}
              />
              <EbNavRow
                icon={<SlidersHorizontal />}
                title="Risk & comfort"
                value={`${(opts.targetCumulativeFailure * 100).toFixed(1)}% over ${opts.horizonYears} ${calendarMode ? "predicted cycles" : "years"}`}
                onClick={() => setSettingsView("risk")}
              />
              <EbNavRow
                icon={<Users />}
                title="Profiles & sharing"
                value={(() => {
                  const active = sharedSyncState
                    ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
                    : null;
                  if (!sharedSyncState || !active) return "Set up sync and sharing";
                  const count = sharedSyncState.profiles.length;
                  return `${count} profile${count === 1 ? "" : "s"} · ${settingsProfileMeta(sharedSyncState, active)}`;
                })()}
                tone={(() => {
                  const active = sharedSyncState
                    ? findProfile(sharedSyncState, sharedSyncState.activeProfileKey)
                    : null;
                  return sharedSyncState && active &&
                    settingsProfileBadge(sharedSyncState, active) === "shared"
                    ? "shared"
                    : "default";
                })()}
                onClick={() => setSettingsView("sharing")}
              />
              {sharedSyncState && sharedSyncState.profiles.length > 0 && (
                <>
                  <EbGroupLabel>Profiles</EbGroupLabel>
                  <EbNavRow
                    icon={<Users />}
                    title="Manage profiles"
                    value={`${sharedSyncState.profiles.length} profile${
                      sharedSyncState.profiles.length === 1 ? "" : "s"
                    } on this device`}
                    onClick={() => setSettingsView("profiles")}
                  />
                </>
              )}
              <EbGroupLabel>Privacy on this browser</EbGroupLabel>
              <div className="card privacy-session-card">
                <div>
                  <strong>
                    {storageMode === "trusted" ? "Trusted browser" : "Temporary session"}
                  </strong>
                  <p className="hint compact">
                    Synced Drive data is decrypted for use here. Anyone who can open this browser
                    profile may be able to see data retained on it.
                  </p>
                </div>
                <div className="row">
                  {storageMode === "trusted" ? (
                    <button type="button" className="ghost" onClick={() => selectStorageMode("temporary")}>
                      Use temporary sessions
                    </button>
                  ) : (
                    <button type="button" className="ghost" onClick={() => selectStorageMode("trusted")}>
                      Trust this browser
                    </button>
                  )}
                  <button
                    type="button"
                    className="ghost danger"
                    disabled={privacyEraseBusy}
                    onClick={() => void endPrivateBrowserSession()}
                  >
                    {privacyEraseBusy ? "Erasing…" : "End session & erase this browser"}
                  </button>
                </div>
              </div>
              <EbGroupLabel>About</EbGroupLabel>
              <EbNavRow
                icon={<Info />}
                title="About EasyBC"
                value="Privacy, source & platform notes"
                onClick={() => setSettingsView("about")}
              />
            </section>
          )}

          {tab === "settings" && settingsView === "basics" && (
            <section className="settings-screen">
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => setSettingsView("hub")}
              >
                ← Settings
              </button>
              <fieldset className="settings-form settings-form-android">
                <legend>Plan basics</legend>
                <label>
                  Age
                  <input
                    type="number"
                    min={15}
                    max={55}
                    value={opts.ageYears}
                    onChange={(e) =>
                      setOpts((o) => ({ ...o, ageYears: Number(e.target.value) }))
                    }
                  />
                </label>
                <label>
                  Baseline cycle length (legacy mode)
                  <input
                    type="number"
                    min={21}
                    max={45}
                    value={opts.cycleLengthDays}
                    onChange={(e) =>
                      setOpts((o) => ({ ...o, cycleLengthDays: Number(e.target.value) }))
                    }
                  />
                </label>
                <button
                  type="button"
                  className="primary-action"
                  onClick={() => {
                    runPlan();
                    setTab("planner");
                  }}
                >
                  {session.plannerConfigured ? "Update plan" : "Generate plan"}
                </button>
              </fieldset>

              <section className="period-panel">
                <h2>Predicted cycles → core</h2>
                <p className="hint">
                  Needs at least <strong>two period starts</strong> in History/Calendar to infer
                  lengths. Then letters on the wall calendar reflect this plan when in calendar mode.
                </p>
                {horizonToday && opts.calendarCycles?.length ? (
                  <p className="hint">
                    Anchor grid: row <strong>{horizonToday.row}</strong>, cycle day{" "}
                    <strong>{horizonToday.dayInCycle}</strong>.
                  </p>
                ) : null}
                <div className="row">
                  <button
                    type="button"
                    onClick={() => applyPredictedCycles(6)}
                    disabled={sortedStarts.length < 2}
                    title="Need at least two starts to infer a length"
                  >
                    Plan with 6 predicted cycles
                  </button>
                  <button type="button" className="ghost" onClick={clearCalendarMode}>
                    Clear calendar-cycle mode
                  </button>
                </div>
                {opts.calendarCycles?.length ? (
                  <p className="hint">
                    Calendar mode: <strong>{opts.calendarCycles.length}</strong> rows (lengths{" "}
                    {opts.calendarCycles.map((c) => c.cycleLengthDays).join(", ")}).
                  </p>
                ) : null}
                <div className="variance-card card">
                  <h3>Cycle length posterior → planner uncertainty</h3>
                  <p className="hint compact">
                    From your logged period starts we infer <strong>{historyLengths.length}</strong>{" "}
                    completed cycle length(s). With fewer than two, cycle-to-cycle spread is unknown
                    and we use age-based defaults only.
                  </p>
                  <p className="meta">
                    Posterior mean next-cycle length:{" "}
                    <strong>{lengthPosterior.mean.toFixed(1)}</strong> day(s). Predictive range:{" "}
                    <strong>
                      {lengthPosterior.lower}-{lengthPosterior.upper}
                    </strong>{" "}
                    day(s).
                  </p>
                  {historyLengths.length >= 2 ? (
                    <p className="meta">
                      Sample SD of lengths: <strong>{lengthSampleSd!.toFixed(2)}</strong> day(s).
                      Extra widening added to baseline ovulation SD:{" "}
                      <strong>{varianceWidenExtra.toFixed(2)}</strong> (capped in core per row).
                      {effectiveRowSd != null && (
                        <>
                          {" "}
                          Effective <code>cycleSdDays</code> on each predicted row:{" "}
                          <strong>{effectiveRowSd.toFixed(2)}</strong> (includes baseline{" "}
                          {opts.ovulationSdDays.toFixed(2)} from inputs).
                        </>
                      )}
                    </p>
                  ) : (
                    <p className="meta">Log at least two cycles to personalize variance widening.</p>
                  )}
                  {varianceWidenExtra > 0 && (
                    <p className="hint">
                      Higher length variability widens the <strong>modeled</strong> fertile window in
                      the optimizer, which usually pushes the plan toward more protected or abstinent
                      days for the same cumulative target, not a diagnosis.
                    </p>
                  )}
                </div>
              </section>
            </section>
          )}

          {tab === "settings" && settingsView === "protection" && (
            <section className="settings-screen">
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => setSettingsView("hub")}
              >
                ← Settings
              </button>
              <fieldset className="settings-form settings-form-android">
                <legend>Protection</legend>
                <ChoiceChipGroup
                  label="Persistent / background method"
                  description="An always-on method that reduces baseline risk for all days."
                  value={opts.persistentMethod}
                  options={PERSISTENT_METHOD_OPTIONS}
                  onChange={(persistentMethod) => setOpts((o) => ({ ...o, persistentMethod }))}
                />
                <ChoiceChipGroup
                  label="Protected-day method"
                  description="Used on days marked C. Controls what protected means in the plan."
                  value={opts.protectedDayMethod}
                  options={PROTECTED_METHOD_OPTIONS}
                  onChange={(protectedDayMethod) => setOpts((o) => ({ ...o, protectedDayMethod }))}
                />
                {opts.protectedDayMethod === "external_condom" && (
                  <ChoiceChipGroup
                    label="Condom use quality"
                    value={opts.condomMode}
                    options={CONDOM_MODE_OPTIONS}
                    onChange={(condomMode) => setOpts((o) => ({ ...o, condomMode }))}
                  />
                )}
                {opts.protectedDayMethod === "external_condom" &&
                  opts.condomMode === "custom" && (
                    <label>
                      Custom condom residual (0-1)
                      <input
                        type="number"
                        step={0.01}
                        min={0}
                        max={1}
                        value={opts.customCondomResidual ?? ""}
                        onChange={(e) =>
                          setOpts((o) => ({
                            ...o,
                            customCondomResidual:
                              e.target.value === "" ? undefined : Number(e.target.value),
                          }))
                        }
                      />
                    </label>
                  )}
                <ChoiceChipGroup
                  label="Withdrawal"
                  description="If enabled, the planner can recommend W on moderate-risk days."
                  value={opts.withdrawalMode}
                  options={WITHDRAWAL_MODE_OPTIONS}
                  onChange={(withdrawalMode) => setOpts((o) => ({ ...o, withdrawalMode }))}
                />
                {opts.withdrawalMode === "custom" ? (
                  <label>
                    Withdrawal relative risk (0-1)
                    <input
                      type="number"
                      step={0.05}
                      min={0}
                      max={1}
                      value={opts.withdrawalRelativeRisk}
                      onChange={(e) =>
                        setOpts((o) => ({
                          ...o,
                          withdrawalRelativeRisk: Number(e.target.value),
                        }))
                      }
                    />
                  </label>
                ) : opts.withdrawalMode === "typical" ? (
                  <label>
                    Withdrawal typical annual failure
                    <input
                      type="number"
                      step={0.01}
                      min={0}
                      max={0.5}
                      value={opts.withdrawalTypicalAnnualFailure}
                      onChange={(e) =>
                        setOpts((o) => ({
                          ...o,
                          withdrawalTypicalAnnualFailure: Number(e.target.value),
                        }))
                      }
                    />
                  </label>
                ) : null}
                {opts.protectedDayMethod !== "none" && opts.withdrawalMode !== "none" && (
                  <label>
                    <input
                      type="checkbox"
                      checked={opts.useWithdrawalBackupOnProtectedDays}
                      onChange={(e) =>
                        setOpts((o) => ({
                          ...o,
                          useWithdrawalBackupOnProtectedDays: e.target.checked,
                        }))
                      }
                    />{" "}
                    Layer withdrawal behind the selected protected-day method
                  </label>
                )}
                {opts.protectedDayMethod !== "none" && opts.withdrawalMode !== "none" && (
                  <label>
                    Combined-method independence (0-1)
                    <input
                      type="number"
                      step={0.05}
                      min={0}
                      max={1}
                      value={opts.combinedMethodIndependence}
                      onChange={(e) =>
                        setOpts((o) => ({
                          ...o,
                          combinedMethodIndependence: Number(e.target.value),
                        }))
                      }
                    />
                    <span className="field-hint">
                      0 keeps no extra benefit from layering methods; 1 assumes full independence.
                    </span>
                  </label>
                )}
                <button
                  type="button"
                  className="primary-action"
                  onClick={() => {
                    runPlan();
                    setTab("planner");
                  }}
                >
                  {session.plannerConfigured ? "Update plan" : "Generate plan"}
                </button>
              </fieldset>
            </section>
          )}

          {tab === "settings" && settingsView === "risk" && (
            <section className="settings-screen">
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => setSettingsView("hub")}
              >
                ← Settings
              </button>
              <fieldset className="settings-form settings-form-android">
                <legend>Risk &amp; comfort</legend>
                <label>
                  {calendarMode
                    ? "Horizon (predicted menstrual cycles)"
                    : "Horizon (calendar years)"}
                  <input
                    type="number"
                    min={1}
                    max={40}
                    value={opts.horizonYears}
                    onChange={(e) =>
                      setOpts((o) => ({ ...o, horizonYears: Number(e.target.value) }))
                    }
                  />
                  <span className="field-hint">
                    {calendarMode ? (
                      <>
                        Each slice is one <strong>forecast cycle</strong> from your log. Not solar
                        years.
                      </>
                    ) : (
                      <>
                        Calendar years forward (Rust <code>horizon_years</code>): one representative
                        cycle per year of age.
                      </>
                    )}
                  </span>
                </label>
                <label>
                  Cumulative risk target over the <strong>entire horizon</strong>
                  <input
                    type="number"
                    step={0.005}
                    min={0}
                    max={0.5}
                    value={opts.targetCumulativeFailure}
                    onChange={(e) =>
                      setOpts((o) => ({
                        ...o,
                        targetCumulativeFailure: Number(e.target.value),
                      }))
                    }
                  />
                </label>
                <div className="derived-field">
                  In-flight incident adjustment
                  <strong>{formatPercent(opts.realizedCumulativeRisk)}</strong>
                  <span className="field-hint">
                    Conditional additional risk not already represented by retained
                    incident-day plan entries. It tightens the remaining plan and
                    releases with a new period. Timed EC uses the model’s
                    least-effective scenario; missing or contradictory timing
                    receives no credit.
                  </span>
                </div>
                <label>
                  Acts per week
                  <input
                    type="number"
                    step={0.1}
                    min={0}
                    value={opts.actsPerWeek}
                    onChange={(e) =>
                      setOpts((o) => ({ ...o, actsPerWeek: Number(e.target.value) }))
                    }
                  />
                </label>
                <div className="settings-subsection-title">
                  <h3>Advanced</h3>
                </div>
                <label>
                  <input
                    type="checkbox"
                    checked={opts.holdLifecycleConstant}
                    onChange={(e) =>
                      setOpts((o) => ({
                        ...o,
                        holdLifecycleConstant: e.target.checked,
                      }))
                    }
                  />{" "}
                  Hold lifecycle constant (no age scaling of cycle / frequency / SD)
                </label>
                <label>
                  <input
                    type="checkbox"
                    checked={applyPastLocks}
                    onChange={(e) => setApplyPastLocks(e.target.checked)}
                  />{" "}
                  With calendar cycles, lock past days only from explicit as-lived logs before
                  computing
                </label>
                <div className="card">
                  <h3>Optional body signals</h3>
                  <p className="hint compact">
                    Applied to horizon year 0 in legacy mode, or the active cycle in calendar
                    mode. Calendar OPK and egg-white mucus logs are detected automatically.
                  </p>
                  <label>
                    LH surge day
                    <input
                      type="number"
                      min={1}
                      max={60}
                      value={opts.bodySignals?.lhSurgeDay ?? ""}
                      onChange={(e) => updateBodySignal("lhSurgeDay", e.target.value)}
                    />
                  </label>
                  <label>
                    Cervical mucus peak day
                    <input
                      type="number"
                      min={1}
                      max={60}
                      value={opts.bodySignals?.cervicalMucusPeakDay ?? ""}
                      onChange={(e) =>
                        updateBodySignal("cervicalMucusPeakDay", e.target.value)
                      }
                    />
                  </label>
                  <label>
                    BBT shift day
                    <input
                      type="number"
                      min={1}
                      max={60}
                      value={opts.bodySignals?.basalBodyTemperatureShiftDay ?? ""}
                      onChange={(e) =>
                        updateBodySignal("basalBodyTemperatureShiftDay", e.target.value)
                      }
                    />
                  </label>
                  <label>
                    Wearable temperature shift day
                    <input
                      type="number"
                      min={1}
                      max={60}
                      value={opts.bodySignals?.wearableTemperatureShiftDay ?? ""}
                      onChange={(e) =>
                        updateBodySignal("wearableTemperatureShiftDay", e.target.value)
                      }
                    />
                  </label>
                </div>
                <button
                  type="button"
                  className="primary-action"
                  onClick={() => {
                    runPlan();
                    setTab("planner");
                  }}
                >
                  {session.plannerConfigured ? "Update plan" : "Generate plan"}
                </button>
              </fieldset>
            </section>
          )}

          {tab === "settings" && settingsView === "sharing" && (
            <section className="settings-screen">
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => setSettingsView("hub")}
              >
                ← Settings
              </button>
              <SyncSettings
                options={opts}
                periodRecords={periodRecords}
                session={session}
                sharedSyncState={sharedSyncState}
                onApplyPayload={applySyncedPayload}
                onSharedSyncStateChange={setSharedSyncState}
                onSyncComplete={markSyncComplete}
              />
            </section>
          )}

          {tab === "settings" && settingsView === "profiles" && (
            <section className="settings-screen">
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => setSettingsView("hub")}
              >
                ← Settings
              </button>
              <div className="screen-heading">
                <p className="eyebrow">Profiles</p>
                <h2>Manage profiles</h2>
                <p>
                  Every profile keeps its own settings, data, storage, and
                  sharing. Switching publishes your current profile first.
                </p>
              </div>
              {switcherProfiles.map((profile) => (
                <EbPersonCard
                  key={profile.key}
                  name={profile.name}
                  colorKey={profile.key}
                  photoUrl={profile.photoUrl}
                >
                  <div className="participant-summary">
                    <span className="field-hint">
                      {profileSwitchingKey === profile.key
                        ? "Switching…"
                        : profile.meta}
                    </span>
                    {profile.active ? (
                      <span className="profile-active-badge">Active</span>
                    ) : (
                      <div className="participant-actions">
                        <button
                          type="button"
                          className="ghost"
                          disabled={profileSwitchingKey !== null}
                          onClick={() => void handleSwitchProfile(profile.key)}
                        >
                          Switch to this profile
                        </button>
                      </div>
                    )}
                  </div>
                </EbPersonCard>
              ))}
              {profileSwitchNotice && (
                <p className="sync-notice sync-notice-error" role="alert">
                  {profileSwitchNotice}
                </p>
              )}
              <div className="profile-switcher-add">
                <input
                  type="text"
                  value={newProfileName}
                  onChange={(event) => setNewProfileName(event.target.value)}
                  placeholder="New profile name"
                  disabled={profileSwitchingKey !== null}
                  aria-label="New profile name"
                />
                <EbButton
                  variant="primary"
                  disabled={profileSwitchingKey !== null || !newProfileName.trim()}
                  onClick={() => {
                    const name = newProfileName.trim();
                    if (!name) return;
                    void handleCreateLocalProfile(name).then((created) => {
                      if (created) setNewProfileName("");
                    });
                  }}
                >
                  ＋ New profile
                </EbButton>
              </div>
              <p className="field-hint">
                New profiles start local to this device — choose Private cloud
                or Shared later in Storage &amp; sharing.
              </p>
              <EbButton variant="outline" onClick={() => setSettingsView("sharing")}>
                Join a shared profile
              </EbButton>
              <EbButton variant="outline" onClick={() => setSettingsView("sharing")}>
                Storage &amp; sharing for this profile
              </EbButton>
            </section>
          )}

          {tab === "settings" && settingsView === "setup" && (
            <section className="settings-screen">
              <div className="screen-heading">
                <p className="eyebrow">Set up</p>
                <h2>
                  {
                    [
                      "Who is this profile for?",
                      "Cycle basics",
                      "Protection",
                      "Risk & comfort",
                      "Where should it live?",
                    ][setupStep]
                  }
                </h2>
              </div>
              <EbStepDots count={5} active={setupStep} />
              {setupStep === 0 && (
                <fieldset className="settings-form settings-form-android">
                  <p className="field-hint">
                    Setting this up for your daughter? Use her name and age — every
                    profile keeps its own settings, data, and sharing.
                  </p>
                  {sharedSyncState && (
                    <div className="appearance-row">
                      {(() => {
                        const active = findProfile(
                          sharedSyncState,
                          sharedSyncState.activeProfileKey,
                        );
                        return (
                          <EbAvatar
                            name={
                              setupName.trim() ||
                              (active
                                ? profileDisplayLabel(sharedSyncState, active)
                                : "Me")
                            }
                            colorKey={sharedSyncState.activeProfileKey}
                            photoUrl={
                              active?.avatarWebp
                                ? avatarDataUrl(active.avatarWebp)
                                : undefined
                            }
                            size="lg"
                          />
                        );
                      })()}
                      <input
                        ref={setupAvatarInputRef}
                        type="file"
                        accept="image/*"
                        style={{ display: "none" }}
                        onChange={(e) => void applySetupAvatar(e.target.files?.[0])}
                      />
                      <EbButton
                        variant="outline"
                        onClick={() => setupAvatarInputRef.current?.click()}
                      >
                        Add photo
                      </EbButton>
                    </div>
                  )}
                  <label>
                    Name
                    <input
                      type="text"
                      placeholder="Emma"
                      value={setupName}
                      onChange={(e) => setSetupName(e.target.value)}
                    />
                  </label>
                  <label>
                    Age
                    <input
                      type="number"
                      min={15}
                      max={55}
                      value={opts.ageYears}
                      onChange={(e) =>
                        setOpts((o) => ({ ...o, ageYears: Number(e.target.value) }))
                      }
                    />
                  </label>
                </fieldset>
              )}
              {setupStep === 1 && (
                <fieldset className="settings-form settings-form-android">
                  <label>
                    Typical cycle length (days)
                    <input
                      type="number"
                      min={20}
                      max={45}
                      value={opts.cycleLengthDays}
                      onChange={(e) =>
                        setOpts((o) => ({ ...o, cycleLengthDays: Number(e.target.value) }))
                      }
                    />
                  </label>
                  <p className="field-hint">
                    A rough guess is fine — the plan recalibrates as you log periods.
                  </p>
                </fieldset>
              )}
              {setupStep === 2 && (
                <fieldset className="settings-form settings-form-android">
                  <ChoiceChipGroup
                    label="Persistent / background method"
                    description="An always-on method that reduces baseline risk for all days."
                    value={opts.persistentMethod}
                    options={PERSISTENT_METHOD_OPTIONS}
                    onChange={(persistentMethod) =>
                      setOpts((o) => ({ ...o, persistentMethod }))
                    }
                  />
                  <ChoiceChipGroup
                    label="Protected-day method"
                    description="Used on days marked C. Controls what protected means in the plan."
                    value={opts.protectedDayMethod}
                    options={PROTECTED_METHOD_OPTIONS}
                    onChange={(protectedDayMethod) =>
                      setOpts((o) => ({ ...o, protectedDayMethod }))
                    }
                  />
                </fieldset>
              )}
              {setupStep === 3 && (
                <fieldset className="settings-form settings-form-android">
                  <label>
                    Planning horizon (years)
                    <input
                      type="number"
                      min={1}
                      max={15}
                      value={opts.horizonYears}
                      onChange={(e) =>
                        setOpts((o) => ({ ...o, horizonYears: Number(e.target.value) }))
                      }
                    />
                  </label>
                  <label>
                    Cumulative risk target
                    <input
                      type="number"
                      step={0.005}
                      min={0}
                      max={0.5}
                      value={opts.targetCumulativeFailure}
                      onChange={(e) =>
                        setOpts((o) => ({
                          ...o,
                          targetCumulativeFailure: Number(e.target.value),
                        }))
                      }
                    />
                  </label>
                  <p className="field-hint">
                    If 100 couples followed this plan for {opts.horizonYears}{" "}
                    {opts.horizonYears === 1 ? "year" : "years"}, about{" "}
                    {Math.round(opts.targetCumulativeFailure * 100)} would expect a
                    pregnancy. You can tune everything else later in Risk &amp; comfort.
                  </p>
                </fieldset>
              )}
              {setupStep === 4 && (
                <fieldset className="settings-form settings-form-android">
                  <p className="field-hint">
                    Your data stays on this device unless you choose otherwise. You can
                    turn on private encrypted cloud sync — or share selected sections
                    with someone — any time in Storage &amp; sharing. A failed cloud
                    setup always leaves the profile safely local, never lost.
                  </p>
                </fieldset>
              )}
              {setupStep < 4 ? (
                <EbButton
                  variant="primary"
                  onClick={() => {
                    if (setupStep === 0) void applySetupName();
                    setSetupStep(setupStep + 1);
                  }}
                >
                  Continue
                </EbButton>
              ) : (
                <>
                  <EbButton variant="primary" onClick={() => finishSetup("hub")}>
                    Finish — keep it on this device
                  </EbButton>
                  <EbButton variant="outline" onClick={() => finishSetup("sharing")}>
                    Finish &amp; open Storage &amp; sharing
                  </EbButton>
                </>
              )}
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => finishSetup("hub")}
              >
                Use defaults &amp; skip setup
              </button>
            </section>
          )}

          {tab === "settings" && settingsView === "about" && (
            <section className="settings-screen">
              <button
                type="button"
                className="ghost settings-back"
                onClick={() => setSettingsView("hub")}
              >
                ← Settings
              </button>
              <EbNavRow
                icon={<Settings />}
                title="Re-run setup walkthrough"
                value="Five quick steps — name, cycle, protection, risk, storage"
                onClick={() => {
                  setSetupStep(0);
                  setSettingsView("setup");
                }}
              />
              <section className="settings-platform-card">
                <h3>About EasyBC</h3>
                <p className="hint">
                  Android keeps <strong>Device Calendar Export</strong>, reminder scheduling, and
                  <strong> Backup File</strong> export/import in its native settings screen. The web
                  app keeps browser-safe settings here and uses <strong>Encrypted Sync</strong>
                  for planner, period, and logged-day data. Web and Android use the same profile,
                  encrypted sync, and sharing model.
                </p>
                <p className="hint">
                  This is not FDA-cleared as contraception. Calculations assume regular cycles.
                  Consult a healthcare provider for medical advice. Plan effectiveness depends on
                  adherence.
                </p>
                <p className="settings-links">
                  <a href={`${import.meta.env.BASE_URL}privacy.html`}>Privacy policy</a>
                  <span aria-hidden>·</span>
                  <a href="https://github.com/keyneom/easy-bc" rel="noreferrer" target="_blank">
                    Source code
                  </a>
                </p>
              </section>
              <DeveloperLogPanel />
            </section>
          )}

          {tab === "planner" && (
            <>
              {plan ? (
                <section className="planner-screen">
                  <PlannerRiskSummaryCard plan={plan} opts={opts} calendarMode={calendarMode} />

                  {plan.warnings?.length ? (
                    <section className="plan-warning-list" aria-label="Planner warnings">
                      {plan.warnings.map((warning, index) => (
                        <p key={`${warning.message}-${index}`} className="warn">
                          {warning.message}
                        </p>
                      ))}
                    </section>
                  ) : null}

                  {planError && <p className="warn">{planError}</p>}

                  <section className="plan-method-card">
                    <p className="eyebrow">Methods used</p>
                    <p>
                      Persistent: <strong>{humanizeMethodLabel(methodLibrary!.persistentMethod)}</strong>
                      {" · "}
                      Protected days:{" "}
                      <strong>{humanizeMethodLabel(methodLibrary!.protectedDayMethod)}</strong>
                      {" · "}
                      Withdrawal: <strong>{humanizeMethodLabel(methodLibrary!.withdrawalMode)}</strong>
                    </p>
                    <button type="button" className="ghost" onClick={() => selectTab("settings")}>
                      Update inputs
                    </button>
                  </section>

                  <section ref={resultRef} className="plan-years-section">
                    <div className="section-title-row">
                      <div>
                        <p className="eyebrow">Year-by-Year Plan</p>
                        <h2>{calendarMode ? "Projected cycle plan" : "Long-range plan"}</h2>
                      </div>
                      <span className="plan-mode-chip">
                        {calendarMode ? "Calendar cycles" : "Representative years"}
                      </span>
                    </div>
                    <div className="plan-year-list">
                      {plan.years.map((year) => (
                        <PlannerYearCard
                          key={`${year.yearIndex}-${year.age}-${year.cycleLengthDays}`}
                          year={year}
                          calendarMode={calendarMode}
                        />
                      ))}
                    </div>
                  </section>

                  <section className="ec-panel">
                    <h2>{EC_COPY.title}</h2>
                    {EC_COPY.body.map((p) => (
                      <p key={p} className="hint">
                        {p}
                      </p>
                    ))}
                    <label>
                      <input
                        type="checkbox"
                        checked={session.ecJournalFlag}
                        onChange={(e) =>
                          setSession((s) => ({
                            ...s,
                            ecJournalFlag: e.target.checked,
                            ecJournalUpdatedAt: new Date().toISOString(),
                          }))
                        }
                      />{" "}
                      {EC_COPY.journalLabel}
                    </label>
                    <p className="field-hint">{EC_COPY.journalHint}</p>
                  </section>

                  <details className="advanced-plan-panel">
                    <summary>Advanced optimizer tools</summary>
                    <section className="incident-panel surface-card">
                      <h2>Log incident on a modeled day</h2>
                      <p className="hint">
                        Locks the as-lived action on this optimizer row and estimates its difference
                        from the recommendation. For a real wall-date incident, use Calendar events;
                        current-cycle events feed realized risk automatically.
                      </p>
                      <div className="row">
                        <select
                          value={incidentChoice}
                          onChange={(e) => setIncidentChoice(e.target.value as IncidentType | "")}
                          aria-label="Incident type"
                        >
                          <option value="">Choose incident…</option>
                          <option value="unprotected_on_abstinence">unprotected on abstinence</option>
                          <option value="condom_on_abstinence">
                            selected protected method on abstinence
                          </option>
                          <option value="condom_failure">protected-method failure</option>
                          <option value="unprotected_instead_of_condom">
                            unprotected instead of protected
                          </option>
                        </select>
                        <input
                          type="number"
                          min={1}
                          max={y?.cycleLengthDays ?? 1}
                          value={incidentDay}
                          onChange={(e) => setIncidentDay(Number(e.target.value))}
                          aria-label="Incident cycle day"
                        />
                        <button type="button" disabled={!incidentChoice || !y} onClick={applyIncident}>
                          Log incident day
                        </button>
                      </div>
                      {incidentChoice && incidentDayWeight && (
                        <p className="meta">
                          Estimated extra risk versus the current recommendation for day{" "}
                          {incidentDay}: <strong>{(incidentAddedRisk * 100).toFixed(3)}%</strong>.
                          Logged action: <strong>{incidentActionForType(incidentChoice)}</strong>.
                        </p>
                      )}
                    </section>

                    <section className="surface-card">
                      <h2>Cycle strip</h2>
                      <label>
                        {calendarMode
                          ? "Which predicted cycle (index)"
                          : "Which horizon year (0 = first year)"}
                        <input
                          type="number"
                          min={0}
                          max={plan.years.length - 1}
                          value={yearIdx}
                          onChange={(e) => {
                            setYearIdx(Number(e.target.value));
                            setPreview(null);
                          }}
                        />
                      </label>
                      {y && (
                        <p className="meta">
                          Age {y.age} · {y.cycleLengthDays} days · SD {y.cycleSdDays.toFixed(2)}
                          {" · "}
                          {y.actsPerWeek.toFixed(2)} acts/wk · cycle risk{" "}
                          {formatPercent(y.cycleRisk)} · annualized risk {formatPercent(y.annualRisk)}
                          {" · "}
                          {y.literalCycle
                            ? "literal projected cycle"
                            : `${y.effectiveCyclesPerYear.toFixed(2)} cycles/year`}
                        </p>
                      )}
                      {locks.length > 0 && (
                        <div className="locks">
                          <h3>Active locks</h3>
                          <ul>
                            {locks.map((l, i) => (
                              <li key={`${l.yearIndex}-${l.day}-${i}`}>
                                {calendarMode ? "Cycle" : "Year"} {l.yearIndex} · day {l.day} →{" "}
                                {l.action}{" "}
                                <button
                                  type="button"
                                  className="ghost"
                                  onClick={() =>
                                    setLocks((prev) =>
                                      prev.filter(
                                        (x) =>
                                          !(
                                            x.yearIndex === l.yearIndex &&
                                            x.day === l.day
                                          ),
                                      ),
                                    )
                                  }
                                >
                                  remove
                                </button>
                              </li>
                            ))}
                          </ul>
                          <div className="row">
                            <button
                              type="button"
                              disabled={previewLoading}
                              onClick={() => void runPreviewAll()}
                            >
                              Preview replan with all locks
                            </button>
                            <button type="button" className="ghost" onClick={() => setLocks([])}>
                              Clear locks
                            </button>
                          </div>
                        </div>
                      )}
                      {y && (
                        <div className="calendar" role="grid" aria-label="Cycle days">
                          {y.dayWeights.map((d) => (
                            <button
                              key={d.day}
                              type="button"
                              className={`day cell-${d.recommendedAction}`}
                              aria-label={`Day ${d.day}, recommended ${d.recommendedAction}`}
                              title={`Day ${d.day}: ${d.recommendedAction} (risk score ${d.rawRiskScore})`}
                              onClick={() => setModalDay(d.day)}
                            >
                              <span className="dn">{d.day}</span>
                              <span className="ac">{d.recommendedAction}</span>
                            </button>
                          ))}
                        </div>
                      )}
                      <p className="hint">
                        Locks / as-lived logs for the <strong>optimizer grid</strong>. The{" "}
                        <strong>Calendar</strong> tab is wall dates; switch there to see the same
                        plan letters when calendar mode is on.
                      </p>
                    </section>

                    {preview && (
                      <section className="preview">
                        <h2>Preview (with locks)</h2>
                        {!preview.feasible && preview.message && (
                          <p className="warn">{preview.message}</p>
                        )}
                        {preview.feasible && (
                          <p>
                            Preview cumulative risk:{" "}
                            <strong>{formatPercent(preview.preview.achievedCumulativeRisk)}</strong>
                            {" — "}
                            target met: <strong>{preview.previewTargetMet ? "yes" : "no"}</strong>
                          </p>
                        )}
                        <p>
                          Baseline was {formatPercent(preview.baseline.achievedCumulativeRisk)};
                          preview {formatPercent(preview.preview.achievedCumulativeRisk)}.
                        </p>
                        {preview.diffs.length > 0 ? (
                          <>
                            <h3>Days that change</h3>
                            <table className="diffs">
                              <thead>
                                <tr>
                                  <th>{calendarMode ? "Cycle #" : "Year #"}</th>
                                  <th>Day</th>
                                  <th>Baseline</th>
                                  <th>Preview</th>
                                </tr>
                              </thead>
                              <tbody>
                                {preview.diffs.map((d, i) => (
                                  <tr key={i}>
                                    <td>{d.yearIndex}</td>
                                    <td>{d.day}</td>
                                    <td>{d.baselineAction}</td>
                                    <td>{d.previewAction}</td>
                                  </tr>
                                ))}
                              </tbody>
                            </table>
                          </>
                        ) : (
                          <p>No calendar changes (same as baseline).</p>
                        )}
                        <div className="row">
                          <button
                            type="button"
                            disabled={!preview.feasible}
                            onClick={() => applyPreview()}
                          >
                            Apply preview as new plan
                          </button>
                          <button type="button" className="ghost" onClick={() => setPreview(null)}>
                            Dismiss preview
                          </button>
                        </div>
                      </section>
                    )}
                  </details>

                  {modalDay !== null && y && (
                    <div
                      className="modal-backdrop"
                      role="dialog"
                      aria-modal="true"
                      aria-labelledby="day-modal-title"
                    >
                      <div className="modal">
                        <h3 id="day-modal-title">
                          Day {modalDay} (
                          {calendarMode ? `predicted cycle ${yearIdx}` : `horizon year ${yearIdx}`})
                          — recommended {y.dayWeights[modalDay - 1]?.recommendedAction}
                        </h3>
                        <p>
                          Pick the action to lock. Add to the list, then run &quot;Preview
                          replan&quot;.
                        </p>
                        <div className="row">
                          {(["U", "W", "C", "A"] as const).map((a) => (
                            <button
                              key={a}
                              type="button"
                              onClick={() => addLockFromModal(modalDay, a)}
                            >
                              Lock as {a}
                            </button>
                          ))}
                        </div>
                        <p className="hint">As-lived log (initial locks for past days)</p>
                        <div className="row">
                          {(["U", "W", "C", "A"] as const).map((a) => (
                            <button
                              key={`log-${a}`}
                              type="button"
                              className="ghost"
                              onClick={() => logAsLivedFromModal(modalDay, a)}
                            >
                              Log {a}
                            </button>
                          ))}
                        </div>
                        <button type="button" className="ghost" onClick={() => setModalDay(null)}>
                          Cancel
                        </button>
                      </div>
                    </div>
                  )}
                </section>
              ) : (
                <section className="empty-state">
                  <ChartSpline size={52} aria-hidden />
                  <h2>No plan yet</h2>
                  <p>Set up your profile in Settings to generate a personalized plan.</p>
                  <button type="button" onClick={() => selectTab("settings")}>Open Settings</button>
                </section>
              )}

              <div className="disclaimer">
                <p>
                  Personal planning tool only — not medical advice or an FDA-cleared contraceptive.
                  All calculation runs locally in your browser.
                </p>
                <p>
                  <strong>Wall calendar</strong> uses your bleeding dates and simple cycle math.
                  <strong> Planner</strong> uses the Rust core. Abstinence credits are a local journal;
                  they do not yet change the numeric optimizer.
                </p>
              </div>
            </>
          )}

          {tab !== "planner" && (
            <div className="disclaimer">
              <p>
                Not medical advice. Calendar phases and fertile windows are <strong>estimates</strong>{" "}
                only.
              </p>
            </div>
          )}
        </>
      )}
      </main>
      {privacyPromptOpen && (
        <div
          className="modal-backdrop"
          role="dialog"
          aria-modal="true"
          aria-labelledby="privacy-session-title"
        >
          <div className="modal privacy-session-modal">
            <ShieldCheck aria-hidden />
            <h2 id="privacy-session-title">Keep decrypted data on this browser?</h2>
            <p>
              EasyBC encrypts data in Google Drive, but data you open is stored locally so the app
              can work. Anyone with access to this browser profile could see that retained data.
            </p>
            <div className="privacy-choice-grid">
              <button type="button" onClick={() => selectStorageMode("temporary")}>
                Temporary session
                <span>
                  Erase EasyBC data, cached Google access, and local identity material when this
                  session ends. Local-only profiles will also be erased.
                </span>
              </button>
              <button type="button" className="ghost" onClick={() => selectStorageMode("trusted")}>
                Trust this browser
                <span>Keep profiles available here for future visits.</span>
              </button>
            </div>
            <p className="hint compact">
              Browser close cleanup is best effort; EasyBC always completes any unfinished
              temporary cleanup before reading data on the next launch.
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
