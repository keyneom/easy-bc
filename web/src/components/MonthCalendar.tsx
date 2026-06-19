import { useMemo } from "react";
import { allBleedingDates, estimateCyclePhase } from "../tracker/cyclePhase";
import { monthGrid, toIsoDate } from "../tracker/calendarMath";
import type { PeriodRecord } from "../tracker/types";
import type { PlannerDayMeta } from "../tracker/plannerWallMeta";

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];

export type CalendarDensity = "compact" | "comfortable";

type Props = {
  year: number;
  monthIndex: number;
  periodRecords: PeriodRecord[];
  ageYears: number;
  todayIso: string;
  voluntaryAbstinence: Record<string, true>;
  plannerDayMeta?: (iso: string) => PlannerDayMeta | null;
  calendarDensity: CalendarDensity;
  onCalendarDensityChange: (d: CalendarDensity) => void;
  onSelectDay: (iso: string) => void;
  onPrevMonth: () => void;
  onNextMonth: () => void;
};

export function MonthCalendar({
  year,
  monthIndex,
  periodRecords,
  ageYears,
  todayIso,
  voluntaryAbstinence,
  plannerDayMeta,
  calendarDensity,
  onCalendarDensityChange,
  onSelectDay,
  onPrevMonth,
  onNextMonth,
}: Props) {
  const cells = useMemo(() => monthGrid(year, monthIndex), [year, monthIndex]);
  const bleeding = useMemo(
    () => allBleedingDates(periodRecords, todayIso),
    [periodRecords, todayIso],
  );
  const phaseByIso = useMemo(() => {
    const m = new Map<string, ReturnType<typeof estimateCyclePhase>>();
    for (const c of cells) {
      if (c.inMonth) m.set(c.iso, estimateCyclePhase(c.iso, periodRecords, ageYears));
    }
    return m;
  }, [cells, periodRecords, ageYears]);

  const title = new Date(year, monthIndex, 1).toLocaleString(undefined, {
    month: "long",
    year: "numeric",
  });

  return (
    <section className="tracker-month" aria-label="Calendar month">
      <div className="tracker-month-nav">
        <button type="button" className="ghost" onClick={onPrevMonth}>
          ←
        </button>
        <h2 className="tracker-month-title">{title}</h2>
        <button type="button" className="ghost" onClick={onNextMonth}>
          →
        </button>
      </div>
      <div className="tracker-density-row" role="group" aria-label="Calendar cell density">
        <span className="tracker-density-label">Planner on cells:</span>
        <label className="tracker-density-option">
          <input
            type="radio"
            name="calendar-density"
            checked={calendarDensity === "comfortable"}
            onChange={() => onCalendarDensityChange("comfortable")}
          />
          Comfortable
        </label>
        <label className="tracker-density-option">
          <input
            type="radio"
            name="calendar-density"
            checked={calendarDensity === "compact"}
            onChange={() => onCalendarDensityChange("compact")}
          />
          Compact
        </label>
      </div>
      <div className="tracker-dow-row" aria-hidden>
        {WEEKDAYS.map((d) => (
          <div key={d} className="tracker-dow">
            {d}
          </div>
        ))}
      </div>
      <div className="tracker-grid" role="grid" aria-label={`${title} days`}>
        {cells.map((c) => {
          const est = phaseByIso.get(c.iso);
          const phase = est?.phase;
          const meta = plannerDayMeta?.(c.iso);
          const action = meta?.recommendedAction;
          const risk = meta?.rawRiskScore;
          const showRisk = calendarDensity === "comfortable" && meta != null;
          const titleTip =
            meta != null
              ? `${c.iso}: plan ${action}, raw risk ${risk}`
              : undefined;
          const classes = [
            "tracker-cell",
            c.inMonth ? "tracker-cell-in-month" : "tracker-cell-pad",
            c.iso === todayIso ? "tracker-cell-today" : "",
            bleeding.has(c.iso) ? "tracker-cell-bleeding" : "",
            phase === "fertile" && c.inMonth && !bleeding.has(c.iso) ? "tracker-cell-fertile" : "",
            voluntaryAbstinence[c.iso] ? "tracker-cell-credit" : "",
            est?.usingSampleCycle && c.inMonth ? "tracker-cell-sample" : "",
            action ? `tracker-cell-action-${action}` : "",
          ]
            .filter(Boolean)
            .join(" ");

          const ariaPlanner =
            meta != null
              ? `, planner ${action}, raw risk score ${risk}`
              : "";

          return (
            <button
              key={c.iso}
              type="button"
              role="gridcell"
              className={classes}
              disabled={!c.inMonth}
              title={c.inMonth ? titleTip : undefined}
              aria-label={
                c.inMonth
                  ? `${c.iso}${c.iso === todayIso ? ", today" : ""}${bleeding.has(c.iso) ? ", bleeding logged" : ""}${ariaPlanner}`
                  : undefined
              }
              aria-current={c.iso === todayIso ? "date" : undefined}
              onClick={() => c.inMonth && onSelectDay(c.iso)}
            >
              <span className="tracker-cell-day">{c.inMonth ? c.day : ""}</span>
              {c.inMonth && meta != null && (
                <span className="tracker-cell-plan-block">
                  <span className="tracker-cell-plan-letter">{action}</span>
                  {showRisk && <span className="tracker-cell-plan-risk">{risk}</span>}
                </span>
              )}
              {c.inMonth && voluntaryAbstinence[c.iso] && (
                <span className="tracker-cell-credit-dot" title="Abstinence credit" />
              )}
            </button>
          );
        })}
      </div>
      <ul className="tracker-legend hint">
        <li>
          <span className="swatch swatch-bleeding" /> Logged bleeding
        </li>
        <li>
          <span className="swatch swatch-fertile" /> Estimated fertile window (calendar-only)
        </li>
        <li>
          <span className="swatch swatch-credit" /> Voluntary abstinence credit
        </li>
        <li>
          <span className="swatch swatch-sample" /> Sample cycle (no data yet)
        </li>
        <li>
          Letter + number = planner <strong>U</strong>/<strong>W</strong>/<strong>C</strong>/
          <strong>A</strong> and raw risk (0–100) when calendar plan is active
        </li>
      </ul>
    </section>
  );
}

export function todayIsoLocal(): string {
  return toIsoDate(new Date());
}
