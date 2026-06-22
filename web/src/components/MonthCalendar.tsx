import { useEffect, useMemo, useState } from "react";
import { CalendarDays, CalendarRange, ChevronLeft, ChevronRight, Grid3X3 } from "lucide-react";
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
  onToday: () => void;
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
  onToday,
}: Props) {
  const [viewMode, setViewMode] = useState<"month" | "week">("month");
  const [weekOffset, setWeekOffset] = useState(0);
  const monthCells = useMemo(() => monthGrid(year, monthIndex), [year, monthIndex]);
  useEffect(() => setWeekOffset(0), [year, monthIndex]);
  const weekCells = useMemo(() => {
    const now = new Date();
    const anchor = now.getFullYear() === year && now.getMonth() === monthIndex
      ? new Date(year, monthIndex, now.getDate())
      : new Date(year, monthIndex, 1);
    const mondayDelta = (anchor.getDay() + 6) % 7;
    const monday = new Date(year, monthIndex, anchor.getDate() - mondayDelta + weekOffset * 7);
    return Array.from({ length: 7 }, (_, index) => {
      const date = new Date(monday.getFullYear(), monday.getMonth(), monday.getDate() + index);
      return { iso: toIsoDate(date), day: date.getDate(), inMonth: true };
    });
  }, [year, monthIndex, weekOffset]);
  const cells = viewMode === "month" ? monthCells : weekCells;
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

  const title = viewMode === "month"
    ? new Date(year, monthIndex, 1).toLocaleString(undefined, { month: "long", year: "numeric" })
    : `${new Date(`${weekCells[0].iso}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" })}–${new Date(`${weekCells[6].iso}T12:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric" })}`;

  const navigatePrevious = () => {
    if (viewMode === "month") onPrevMonth();
    else setWeekOffset((offset) => offset - 1);
  };

  const navigateNext = () => {
    if (viewMode === "month") onNextMonth();
    else setWeekOffset((offset) => offset + 1);
  };

  return (
    <section className="tracker-month" aria-label="Calendar month">
      <div className="tracker-month-nav">
        <button type="button" className="icon-button" aria-label="Previous" onClick={navigatePrevious}>
          <ChevronLeft aria-hidden />
        </button>
        <h2 className="tracker-month-title">{title}</h2>
        <button type="button" className="icon-button" aria-label="Next" onClick={navigateNext}>
          <ChevronRight aria-hidden />
        </button>
      </div>
      <div className="calendar-view-row">
        <div className="segmented-control" role="group" aria-label="Calendar view">
          <button
            type="button"
            aria-pressed={viewMode === "month"}
            onClick={() => {
              setViewMode("month");
              onCalendarDensityChange("comfortable");
            }}
          >
            <Grid3X3 aria-hidden /> Month
          </button>
          <button
            type="button"
            aria-pressed={viewMode === "week"}
            onClick={() => {
              setViewMode("week");
              onCalendarDensityChange("compact");
            }}
          >
            <CalendarRange aria-hidden /> Week
          </button>
        </div>
        <button
          type="button"
          className="today-button"
          onClick={() => {
            setWeekOffset(0);
            onToday();
          }}
        >
          <CalendarDays aria-hidden /> Today
        </button>
      </div>
      <div className="tracker-dow-row" aria-hidden>
        {WEEKDAYS.map((d) => (
          <div key={d} className="tracker-dow">
            {d}
          </div>
        ))}
      </div>
      <div className={`tracker-grid tracker-grid-${viewMode}`} role="grid" aria-label={`${title} days`}>
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
