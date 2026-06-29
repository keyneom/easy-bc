import { useEffect, useMemo, useState } from "react";
import { CalendarDays, CalendarRange, ChevronLeft, ChevronRight, Grid3X3 } from "lucide-react";
import { derivedBleedingEnd, estimateCyclePhase } from "../tracker/cyclePhase";
import { compareIso, eachIsoInRange, monthGrid, toIsoDate } from "../tracker/calendarMath";
import type { PeriodRecord } from "../tracker/types";
import type { PlannerDayMeta } from "../tracker/plannerWallMeta";
import type { CalendarDayLog, PlannerAction } from "../sessionUtils";

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const ACTION_ORDER: PlannerAction[] = ["U", "W", "C", "A"];
const ACTION_LABELS: Record<PlannerAction, string> = {
  U: "Unprotected",
  W: "Withdrawal",
  C: "Protected",
  A: "Abstain",
};

const PHASE_LABELS: Record<string, string> = {
  menstrual: "Period",
  follicular: "Follicular",
  fertile: "Fertile",
  luteal: "Luteal",
  unknown: "Unknown",
};

export type CalendarDensity = "compact" | "comfortable";

type Props = {
  year: number;
  monthIndex: number;
  periodRecords: PeriodRecord[];
  ageYears: number;
  todayIso: string;
  selectedDayIso: string | null;
  voluntaryAbstinence: Record<string, true>;
  calendarDayLogs: Record<string, CalendarDayLog>;
  activeActions: PlannerAction[];
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
  selectedDayIso,
  voluntaryAbstinence,
  calendarDayLogs,
  activeActions,
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
  const periodStatus = useMemo(() => {
    const sorted = [...periodRecords].sort((a, b) => compareIso(a.start, b.start));
    const status = new Map<string, { predicted: boolean }>();
    for (const record of sorted) {
      const end = derivedBleedingEnd(record, sorted, todayIso);
      for (const iso of eachIsoInRange(record.start, end)) {
        status.set(iso, { predicted: record.end == null });
      }
    }
    return status;
  }, [periodRecords, todayIso]);
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
    <section className={`tracker-month tracker-density-${calendarDensity}`} aria-label="Calendar month">
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
          const period = periodStatus.get(c.iso);
          const isBleeding = period != null;
          const isSelected = selectedDayIso === c.iso;
          const hasDayLog = hasMeaningfulDayLog(calendarDayLogs[c.iso]);
          const showRisk = viewMode === "week" && meta != null;
          const titleTip =
            meta != null
              ? `${c.iso}: plan ${action}, raw risk ${risk}`
              : undefined;
          const classes = [
            "tracker-cell",
            c.inMonth ? "tracker-cell-in-month" : "tracker-cell-pad",
            c.iso === todayIso ? "tracker-cell-today" : "",
            isSelected ? "tracker-cell-selected" : "",
            isBleeding ? "tracker-cell-bleeding" : "",
            period?.predicted ? "tracker-cell-bleeding-predicted" : "",
            phase === "fertile" && c.inMonth && !isBleeding ? "tracker-cell-fertile" : "",
            voluntaryAbstinence[c.iso] ? "tracker-cell-credit" : "",
            hasDayLog ? "tracker-cell-logged" : "",
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
                  ? `${c.iso}${c.iso === todayIso ? ", today" : ""}${isBleeding ? ", bleeding" : ""}${hasDayLog ? ", logged data" : ""}${ariaPlanner}`
                  : undefined
              }
              aria-current={c.iso === todayIso ? "date" : undefined}
              onClick={() => c.inMonth && onSelectDay(c.iso)}
            >
              <span className="tracker-cell-day">{c.inMonth ? c.day : ""}</span>
              {c.inMonth && isBleeding && (
                <span
                  className={`tracker-cell-period-dot ${
                    period?.predicted ? "tracker-cell-period-dot-predicted" : ""
                  }`}
                  title={period?.predicted ? "Predicted/open period" : "Confirmed period"}
                />
              )}
              {c.inMonth && meta != null && (
                <span className="tracker-cell-plan-block">
                  <span className="tracker-cell-plan-letter">{action}</span>
                  {showRisk && <span className="tracker-cell-plan-risk">Risk {risk}</span>}
                  {showRisk && phase && (
                    <span className="tracker-cell-phase">{PHASE_LABELS[phase] ?? phase}</span>
                  )}
                </span>
              )}
              {c.inMonth && hasDayLog && (
                <span className="tracker-cell-log-dot" title="Logged day data" />
              )}
              {c.inMonth && voluntaryAbstinence[c.iso] && (
                <span className="tracker-cell-credit-dot" title="Abstinence credit" />
              )}
            </button>
          );
        })}
      </div>
      <ul className="tracker-legend hint">
        {ACTION_ORDER.filter((action) => activeActions.includes(action)).map((action) => (
          <li key={action}>
            <span className={`swatch swatch-action-${action}`} /> {ACTION_LABELS[action]}
          </li>
        ))}
        <li>
          <span className="swatch swatch-bleeding" /> Period
        </li>
        <li>
          <span className="swatch swatch-bleeding-predicted" /> Predicted/open period
        </li>
        <li>
          <span className="swatch swatch-log" /> Logged day data
        </li>
        <li>
          <span className="swatch swatch-fertile" /> Fertile estimate
        </li>
        <li>
          <span className="swatch swatch-credit" /> Abstinence credit
        </li>
      </ul>
    </section>
  );
}

function hasMeaningfulDayLog(log: CalendarDayLog | undefined): boolean {
  if (!log) return false;
  if (log.actualAction && log.actualAction !== "NONE") return true;
  if (log.notes?.trim()) return true;
  if (log.mucus || log.opk || log.bbtCelsius != null) return true;
  if (log.mittelschmerz || log.breastTender || log.reconciled) return true;
  if (log.events?.length) return true;
  return false;
}

export function todayIsoLocal(): string {
  return toIsoDate(new Date());
}
