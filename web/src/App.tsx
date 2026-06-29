import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  CalendarDays,
  ChartSpline,
  CheckCircle,
  History,
  RefreshCw,
  Settings,
  ShieldCheck,
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
  KV_SYNC_STATE,
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
import { currentRpId, passkeysSupported } from "./sync/passkey";
import {
  buildLocalSyncPayload,
  formatLastSync,
  rememberSyncState,
  runEncryptedSyncOperation,
  syncPayloadFingerprint,
} from "./sync/sessionSync";
import {
  plannerConfiguredFromPayload,
  portablePlannerOptions,
  type LocalSyncState,
  type SyncPayloadV1,
} from "./sync/types";
import {
  computeInFlightRealizedRisk,
  type EcEffectFn,
  type InFlightDay,
} from "./tracker/realizedRisk";

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

export default function App() {
  const resultRef = useRef<HTMLElement | null>(null);
  const optionsFingerprintRef = useRef("");
  const autoSyncFingerprintRef = useRef("");
  const autoSyncRunningRef = useRef(false);
  const autoSyncQueuedRef = useRef(false);
  const riskInputFingerprintRef = useRef("");
  const [wasmReady, setWasmReady] = useState(false);
  const [wasmError, setWasmError] = useState<string | null>(null);
  const [storageReady, setStorageReady] = useState(false);
  const [tab, setTab] = useState<AppTab>("tracker");
  const initDate = useMemo(() => new Date(), []);
  const [viewYear, setViewYear] = useState(initDate.getFullYear());
  const [viewMonth, setViewMonth] = useState(initDate.getMonth());
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
  const [syncState, setSyncState] = useState<LocalSyncState | null>(null);
  const [autoSyncNotice, setAutoSyncNotice] = useState<{
    kind: "info" | "success" | "error";
    message: string;
  } | null>(null);
  const syncClientId = import.meta.env.VITE_GOOGLE_WEB_CLIENT_ID?.trim() ?? "";
  const syncRpId = useMemo(currentRpId, []);

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
      const savedSync = await idbGet<LocalSyncState>(KV_SYNC_STATE);
      const s = hydratePersistedSession(raw, pr.length);
      setSession(s);
      setLocks(s.locks);
      setSyncState(savedSync ?? null);
      const loadedOptions: WasmOptions = {
        ...defaultOptions(),
        ...savedOptions,
        realizedCumulativeRisk: s.realizedCumulativeRisk,
      };
      optionsFingerprintRef.current = JSON.stringify(portablePlannerOptions(loadedOptions));
      setOpts(loadedOptions);
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
    };
    optionsFingerprintRef.current = JSON.stringify(portablePlannerOptions(nextOptions));
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
  }, [session]);

  const localSyncFingerprint = useMemo(
    () => syncPayloadFingerprint(buildLocalSyncPayload(opts, periodRecords, session)),
    [opts, periodRecords, session],
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
    autoSyncFingerprintRef.current = payload ? syncPayloadFingerprint(payload) : "";
  }, []);

  const runAutoSync = useCallback(
    async (reason: "startup" | "foreground" | "change") => {
      if (!syncState) return;
      if (!syncClientId) {
      setAutoSyncNotice({
        kind: "error",
        message: "Encrypted cloud sync is enabled, but this build is missing its Google web client ID.",
      });
        return;
      }
      if (!passkeysSupported()) {
      setAutoSyncNotice({
        kind: "error",
        message: "Encrypted cloud sync is enabled, but this browser cannot use passkeys here.",
      });
        return;
      }
      if (autoSyncRunningRef.current) {
        autoSyncQueuedRef.current = true;
        return;
      }

      autoSyncRunningRef.current = true;
      const { options, periodRecords: records, session: currentSession, fingerprint } =
        latestSyncInputsRef.current;
      autoSyncFingerprintRef.current = fingerprint;
      setAutoSyncNotice({
        kind: "info",
        message:
          reason === "change"
            ? "Merging encrypted cloud changes…"
            : reason === "startup"
            ? "Checking encrypted cloud sync…"
            : "Checking encrypted cloud changes…",
      });

      try {
        const local = buildLocalSyncPayload(options, records, currentSession);
        const result = await runEncryptedSyncOperation({
          operation: "sync",
          clientId: syncClientId,
          rpId: syncRpId,
          local,
        });
        if (result.operation !== "sync") return;
        autoSyncFingerprintRef.current = syncPayloadFingerprint(result.payload);
        await applySyncedPayload(result.payload);
        const nextState = await rememberSyncState(result.fileId, syncRpId, result.syncedAt);
        setSyncState(nextState);
        setAutoSyncNotice({
          kind: "success",
          message: `Encrypted cloud sync updated ${formatLastSync(result.syncedAt)}.`,
        });
      } catch (error) {
        setAutoSyncNotice({
          kind: "error",
          message: `Encrypted cloud sync needs attention: ${
            error instanceof Error ? error.message : String(error)
          }`,
        });
      } finally {
        autoSyncRunningRef.current = false;
        if (autoSyncQueuedRef.current) {
          autoSyncQueuedRef.current = false;
          window.setTimeout(() => void runAutoSync("change"), 250);
        }
      }
    },
    [applySyncedPayload, syncClientId, syncRpId, syncState],
  );

  useEffect(() => {
    if (syncState) return;
    autoSyncFingerprintRef.current = "";
    autoSyncQueuedRef.current = false;
    setAutoSyncNotice(null);
  }, [syncState]);

  useEffect(() => {
    if (!storageReady || !wasmReady || !syncState) return;
    if (autoSyncFingerprintRef.current === "") {
      autoSyncFingerprintRef.current = localSyncFingerprint;
      const h = window.setTimeout(() => void runAutoSync("startup"), 1_500);
      return () => window.clearTimeout(h);
    }
    if (autoSyncFingerprintRef.current === localSyncFingerprint) return;
    const h = window.setTimeout(() => void runAutoSync("change"), 1_800);
    return () => window.clearTimeout(h);
  }, [localSyncFingerprint, runAutoSync, storageReady, syncState, wasmReady]);

  useEffect(() => {
    if (!storageReady || !wasmReady || !syncState) return;
    const syncWhenVisible = () => {
      if (document.visibilityState === "visible") void runAutoSync("foreground");
    };
    document.addEventListener("visibilitychange", syncWhenVisible);
    return () => document.removeEventListener("visibilitychange", syncWhenVisible);
  }, [runAutoSync, storageReady, syncState, wasmReady]);

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
        <div className="privacy-chip">
          <ShieldCheck size={16} aria-hidden />
          Local-first
        </div>
      </header>

      <nav className="app-tabs" role="tablist" aria-label="Main sections">
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "tracker"}
          onClick={() => setTab("tracker")}
        >
          <CalendarDays aria-hidden />
          Calendar
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "planner"}
          onClick={() => setTab("planner")}
        >
          <ChartSpline aria-hidden />
          Plan
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "history"}
          onClick={() => setTab("history")}
        >
          <History aria-hidden />
          History
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "settings"}
          onClick={() => setTab("settings")}
        >
          <Settings aria-hidden />
          Settings
        </button>
      </nav>

      <main className="app-content">
      {!wasmReady && !wasmError && <p className="loading-state">Loading planner…</p>}
      {wasmError && <p className="warn">Planner failed to load: {wasmError}</p>}
      {syncState && autoSyncNotice && (
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
                      [selectedDayIso]: (() => {
                        const next = {
                          ...current.calendarDayLogs[selectedDayIso],
                          ...patch,
                          updatedAt: new Date().toISOString(),
                        } satisfies CalendarDayLog;
                        if ("actualAction" in patch) {
                          next.reconciled = patch.actualAction ? true : undefined;
                        }
                        return next;
                      })(),
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

          {tab === "settings" && (
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
              <fieldset className="settings-form settings-form-android">
                <legend>Profile</legend>
                <div className="settings-subsection-title">
                  <h3>Profile</h3>
                </div>
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
                <div className="settings-subsection-title">
                  <h3>Risk Target</h3>
                </div>
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
                <div className="settings-subsection-title">
                  <h3>Behavior</h3>
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
                <div className="settings-subsection-title">
                  <h3>Contraceptive Methods</h3>
                </div>
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
                <div className="settings-subsection-title">
                  <h3>Preferences &amp; Advanced</h3>
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

              <div className="settings-subsection-title settings-subsection-outside">
                <h3>Encrypted Cloud Sync</h3>
              </div>
              <SyncSettings
                options={opts}
                periodRecords={periodRecords}
                session={session}
                syncState={syncState}
                onApplyPayload={applySyncedPayload}
                onSyncStateChange={setSyncState}
                onSyncComplete={markSyncComplete}
              />
              <section className="settings-platform-card">
                <h3>Platform-specific settings</h3>
                <p className="hint">
                  Android keeps <strong>Device Calendar Export</strong>, reminder scheduling, and
                  <strong> Backup File</strong> export/import in its native settings screen. The web
                  app keeps browser-safe settings here and uses <strong>Encrypted Cloud Sync</strong>
                  for shared planner, period, and logged-day data.
                </p>
                <p className="settings-links">
                  <a href={`${import.meta.env.BASE_URL}privacy.html`}>Privacy policy</a>
                  <span aria-hidden>·</span>
                  <a href="https://github.com/keyneom/easy-bc" rel="noreferrer" target="_blank">
                    Source code
                  </a>
                </p>
              </section>

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
                    <button type="button" className="ghost" onClick={() => setTab("settings")}>
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
                  <button type="button" onClick={() => setTab("settings")}>Open Settings</button>
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
    </div>
  );
}
