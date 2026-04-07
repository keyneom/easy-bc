import { useCallback, useEffect, useMemo, useState } from "react";
import init, { planFertilityRiskJson, replanPreviewJson } from "../pkg/planner_core.js";
import {
  buildCalendarCycles,
  currentCycleDayFromLastStart,
  inferCycleLengthsFromStarts,
  sampleStdDev,
  sdWidenFromVariance,
  type CalendarCycleRow,
} from "./periodTracker";
import { migrateLegacyPeriodStartsIfNeeded, idbGet, idbSet, KV_SESSION } from "./idbStore";
import {
  addPeriodStartDate,
  loadPeriodRecords,
  periodStartsFromRecords,
  removePeriodRecord,
  setPeriodEnd,
  type PeriodRecord,
} from "./tracker/periodRecordsStore";
import { daysBetweenInclusive } from "./tracker/calendarMath";
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
  initialLocksForPastDays,
  INCIDENT_REALIZED_DELTA,
  resolveHorizonRowAndDay,
  type DayLock,
  type PersistedSession,
  type PlannerAction,
} from "./sessionUtils";
import { EC_COPY } from "./strings";
import { DayDetailPanel } from "./components/DayDetailPanel";
import { MonthCalendar, todayIsoLocal, type CalendarDensity } from "./components/MonthCalendar";

interface DayWeight {
  day: number;
  recommendedAction: PlannerAction;
  rawRiskScore: number;
  overrideCost: {
    condoms: number;
    abstinenceDays: number;
    note: string;
  };
}

interface YearOut {
  yearIndex: number;
  age: number;
  cycleLengthDays: number;
  cycleSdDays: number;
  actsPerWeek: number;
  dayWeights: DayWeight[];
}

interface PlannerResult {
  achievedCumulativeRisk: number;
  targetMet: boolean;
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
  condomMode: "typical" | "perfect" | "custom";
  streakAversion: number;
  holdLifecycleConstant: boolean;
  realizedCumulativeRisk: number;
  withdrawalRelativeRisk: number;
  ovulationSdDays: number;
  calendarCycles?: CalendarCycleRow[];
  customCondomResidual?: number;
};

const defaultOptions = (): WasmOptions => ({
  ageYears: 34,
  horizonYears: 20,
  targetCumulativeFailure: 0.05,
  cycleLengthDays: 28,
  actsPerWeek: 3.5,
  condomMode: "perfect",
  streakAversion: 0.5,
  holdLifecycleConstant: false,
  realizedCumulativeRisk: 0,
  withdrawalRelativeRisk: 0.35,
  ovulationSdDays: 3.0,
});

function optionsForWasm(
  o: WasmOptions,
  initialActionLocks?: DayLock[],
): Record<string, unknown> {
  const out: Record<string, unknown> = { ...o };
  if (!o.calendarCycles?.length) delete out.calendarCycles;
  if (initialActionLocks?.length) {
    out.initialActionLocks = initialActionLocks.map((l) => ({
      yearIndex: l.yearIndex,
      day: l.day,
      action: l.action,
    }));
  }
  return out;
}

type AppTab = "tracker" | "planner" | "history";

export default function App() {
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
  const [incidentChoice, setIncidentChoice] = useState<string>("");
  const [calendarDensity, setCalendarDensity] = useState<CalendarDensity>("comfortable");

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
      const s = raw
        ? {
            ...defaultPersistedSession(),
            ...raw,
            dayLogs: raw.dayLogs ?? {},
            voluntaryAbstinenceDates: raw.voluntaryAbstinenceDates ?? {},
          }
        : defaultPersistedSession();
      setSession(s);
      setLocks(s.locks);
      setOpts((o) => ({ ...o, realizedCumulativeRisk: s.realizedCumulativeRisk }));
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

  const runPlan = useCallback(() => {
    if (!wasmReady) return;
    setPlanError(null);
    setPreview(null);
    setLocks([]);
    setSession((s) => ({ ...s, locks: [] }));
    try {
      const lengths = opts.calendarCycles?.map((c) => c.cycleLengthDays) ?? [];
      const sorted = [...sortedStarts].sort();
      let initialLocks: DayLock[] | undefined;
      if (applyPastLocks && lengths.length > 0 && sorted.length > 0) {
        const first = sorted[0];
        const daysSince = daysSinceFirstCycleStart(first);
        const pos = resolveHorizonRowAndDay(lengths, daysSince);
        if (pos) {
          initialLocks = initialLocksForPastDays(lengths, pos, session.dayLogs, "U");
        }
      }
      const json = planFertilityRiskJson(
        JSON.stringify(optionsForWasm(opts, initialLocks)),
      );
      setPlan(JSON.parse(json) as PlannerResult);
      setYearIdx(0);
    } catch (e) {
      setPlanError(String(e));
      setPlan(null);
    }
  }, [wasmReady, opts, applyPastLocks, sortedStarts, session.dayLogs]);

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

  const applyIncident = useCallback(() => {
    if (!incidentChoice || !(incidentChoice in INCIDENT_REALIZED_DELTA)) return;
    const d = INCIDENT_REALIZED_DELTA[incidentChoice];
    setOpts((o) => ({
      ...o,
      realizedCumulativeRisk: Math.min(0.5, o.realizedCumulativeRisk + d),
    }));
    setIncidentChoice("");
  }, [incidentChoice]);

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

  return (
    <>
      <h1>easy-bc</h1>
      <p className="lede">
        Local-first birth control planner: a real <strong>calendar</strong> for periods and optional
        voluntary-abstinence credits, plus the Rust optimizer (WASM) for recommended days when you
        enable predicted-cycle mode.
      </p>

      <nav className="app-tabs" role="tablist" aria-label="Main sections">
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "tracker"}
          onClick={() => setTab("tracker")}
        >
          Calendar
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "planner"}
          onClick={() => setTab("planner")}
        >
          Planner
        </button>
        <button
          type="button"
          role="tab"
          className="app-tab"
          aria-selected={tab === "history"}
          onClick={() => setTab("history")}
        >
          History
        </button>
      </nav>

      {!wasmReady && !wasmError && <p>Loading WebAssembly…</p>}
      {wasmError && <p className="warn">WASM failed to load: {wasmError}</p>}

      {wasmReady && (
        <>
          {tab === "tracker" && (
            <section className="tracker-shell">
              <p className="hint">
                Tap a day to log <strong>period start</strong>, <strong>last bleeding day</strong>, or
                a voluntary-abstinence <strong>credit</strong>. Fertile shading is a simple calendar
                estimate (ovulation ≈ length − 14 ± 5 days), not ovulation-stick accuracy. Inspired by
                common period-tracker UX — we do not ship their full feature sets.
              </p>
              <p className="meta">
                Voluntary abstinence credits (days you chose not to have sex when you could have):{" "}
                <strong>{creditCount}</strong> — stored locally; v1 is a journal count (planner does not
                auto-spend credits yet).
              </p>
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
                todayIso={todayIsoLocal()}
                voluntaryAbstinence={session.voluntaryAbstinenceDates}
                plannerDayMeta={plannerMetaForDate}
                calendarDensity={calendarDensity}
                onCalendarDensityChange={setCalendarDensity}
                onSelectDay={(iso) => setSelectedDayIso(iso)}
                onPrevMonth={goPrevMonth}
                onNextMonth={goNextMonth}
              />
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
                onToggleCredit={() => {
                  if (!selectedDayIso) return;
                  setSession((s) => {
                    const v = { ...s.voluntaryAbstinenceDates };
                    if (v[selectedDayIso]) delete v[selectedDayIso];
                    else v[selectedDayIso] = true;
                    return { ...s, voluntaryAbstinenceDates: v };
                  });
                }}
                onMarkPeriodStart={() => {
                  if (!selectedDayIso) return;
                  void addPeriodStartDate(selectedDayIso).then(setPeriodRecords);
                }}
                onMarkPeriodEnd={() => {
                  if (!selectedDayIso) return;
                  void setPeriodEnd(selectedDayIso).then(setPeriodRecords);
                }}
                calendarPlanActive={calendarMode && Boolean(plan)}
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
                      const len =
                        end != null ? daysBetweenInclusive(r.start, end) : null;
                      return (
                        <tr key={r.start}>
                          <td>{r.start}</td>
                          <td>{end ?? "— (open / until today)"}</td>
                          <td>{len ?? "—"}</td>
                          <td>
                            <button
                              type="button"
                              className="ghost"
                              onClick={() =>
                                void removePeriodRecord(r.start).then(setPeriodRecords)
                              }
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

          {tab === "planner" && (
            <>
              <fieldset>
                <legend>Planner inputs</legend>
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
                <label>
                  Already-used risk (logged exposures)
                  <input
                    type="number"
                    step={0.001}
                    min={0}
                    max={0.5}
                    value={opts.realizedCumulativeRisk}
                    onChange={(e) =>
                      setOpts((o) => ({
                        ...o,
                        realizedCumulativeRisk: Number(e.target.value),
                      }))
                    }
                  />
                  <span className="field-hint">
                    Session autosaves to IndexedDB with calendar credits and locks.
                  </span>
                </label>
                <label>
                  Withdrawal relative risk (0–1)
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
                <label>
                  Condom mode
                  <select
                    value={opts.condomMode}
                    onChange={(e) =>
                      setOpts((o) => ({
                        ...o,
                        condomMode: e.target.value as WasmOptions["condomMode"],
                      }))
                    }
                  >
                    <option value="typical">typical</option>
                    <option value="perfect">perfect</option>
                    <option value="custom">custom</option>
                  </select>
                </label>
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
                  With calendar cycles, lock past days from as-lived logs (default U if missing)
                  before computing
                </label>
                <button type="button" onClick={runPlan}>
                  Compute plan
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
                  <h3>Cycle length variance → planner ovulation SD</h3>
                  <p className="hint compact">
                    From your logged period starts we infer <strong>{historyLengths.length}</strong>{" "}
                    completed cycle length(s). With fewer than two, cycle-to-cycle spread is unknown
                    and we use age-based defaults only.
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
                      the optimizer, which usually pushes the plan toward more condom/abstinence days
                      for the same cumulative target — not a diagnosis.
                    </p>
                  )}
                </div>
              </section>

              <section className="incident-panel">
                <h2>Log incident (placeholder Δ risk)</h2>
                <p className="hint">
                  See <code>docs/incidents.md</code>. Adds to <strong>already-used risk</strong>.
                </p>
                <div className="row">
                  <select
                    value={incidentChoice}
                    onChange={(e) => setIncidentChoice(e.target.value)}
                    aria-label="Incident type"
                  >
                    <option value="">Choose incident…</option>
                    {(Object.keys(INCIDENT_REALIZED_DELTA) as string[]).map((k) => (
                      <option key={k} value={k}>
                        {k} (+{(INCIDENT_REALIZED_DELTA[k] * 100).toFixed(2)}%)
                      </option>
                    ))}
                  </select>
                  <button type="button" disabled={!incidentChoice} onClick={applyIncident}>
                    Apply to realized risk
                  </button>
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
                      setSession((s) => ({ ...s, ecJournalFlag: e.target.checked }))
                    }
                  />{" "}
                  {EC_COPY.journalLabel}
                </label>
                <p className="field-hint">{EC_COPY.journalHint}</p>
              </section>

              {planError && <p className="warn">{planError}</p>}

              {plan && (
                <>
                  <section>
                    <h2>Result</h2>
                    <p>
                      Achieved cumulative risk:{" "}
                      <strong>{(plan.achievedCumulativeRisk * 100).toFixed(2)}%</strong>
                      {" — "}
                      target met: <strong>{plan.targetMet ? "yes" : "no"}</strong>
                    </p>
                  </section>

                  <section>
                    <h2>Cycle strip (optimizer row)</h2>
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
                        Age {y.age} · {y.cycleLengthDays} days · SD {y.cycleSdDays.toFixed(2)} ·{" "}
                        {y.actsPerWeek.toFixed(2)} acts/wk
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
                      <strong>Calendar</strong> tab is wall dates; switch there to see the same plan
                      letters when calendar mode is on.
                    </p>
                  </section>

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

                  {preview && (
                    <section className="preview">
                      <h2>Preview (with locks)</h2>
                      {!preview.feasible && preview.message && (
                        <p className="warn">{preview.message}</p>
                      )}
                      {preview.feasible && (
                        <p>
                          Preview cumulative risk:{" "}
                          <strong>
                            {(preview.preview.achievedCumulativeRisk * 100).toFixed(2)}%
                          </strong>
                          {" — "}
                          target met:{" "}
                          <strong>{preview.previewTargetMet ? "yes" : "no"}</strong>
                        </p>
                      )}
                      <p>
                        Baseline was {(preview.baseline.achievedCumulativeRisk * 100).toFixed(2)}%;
                        preview {(preview.preview.achievedCumulativeRisk * 100).toFixed(2)}%.
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
                </>
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
    </>
  );
}
