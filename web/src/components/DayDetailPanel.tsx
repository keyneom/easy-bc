import { useState } from "react";
import type { CyclePhaseEstimate } from "../tracker/cyclePhase";
import type { PlannerDayMeta } from "../tracker/plannerWallMeta";
import { X } from "lucide-react";
import type { CalendarDayLog, DayEvent, EcType, PlannerAction } from "../sessionUtils";
import { EC_CYCLE_EFFECTS_COPY } from "../strings";

type AddEventKind = "condom_broke" | "unplanned_unprotected" | "plan_b_taken";

export type MethodRiskRow = {
  action: PlannerAction;
  expectedDayPattern: number;
  expectedSingleAct: number;
  plausibleLow: number;
  plausibleHigh: number;
  peakAligned: number;
};

const EVENT_LABEL: Record<DayEvent["kind"], string> = {
  condom_broke: "Condom broke",
  unplanned_unprotected: "Unplanned unprotected",
  plan_b_taken: "Emergency contraception",
};

const EC_LABEL: Record<EcType, string> = {
  levonorgestrel: "Plan B (levonorgestrel)",
  ulipristal: "ella (ulipristal)",
  copper_iud: "Copper IUD",
};

function createId(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) {
    return crypto.randomUUID();
  }
  return `evt-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function formatEventTime(occurredAt: string): string {
  const t = new Date(occurredAt);
  if (Number.isNaN(t.getTime())) return occurredAt;
  return t.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
}

function timestampOnSelectedDate(iso: string): string {
  const now = new Date();
  const local = new Date(
    `${iso}T${String(now.getHours()).padStart(2, "0")}:` +
      `${String(now.getMinutes()).padStart(2, "0")}:00`,
  );
  return Number.isNaN(local.getTime()) ? `${iso}T12:00:00.000Z` : local.toISOString();
}

function describeEvent(event: DayEvent): string {
  switch (event.kind) {
    case "condom_broke":
    case "unplanned_unprotected":
      return EVENT_LABEL[event.kind];
    case "plan_b_taken": {
      const hours =
        event.hoursFromAct != null ? ` · ${event.hoursFromAct}h after act` : "";
      return `${EC_LABEL[event.ecType]}${hours}`;
    }
  }
}

type Props = {
  iso: string | null;
  onClose: () => void;
  estimate: CyclePhaseEstimate | null;
  plannerMeta: PlannerDayMeta | null;
  isBleeding: boolean;
  hasCredit: boolean;
  onToggleCredit: () => void;
  onMarkPeriodStart: () => void;
  onMarkPeriodEnd: () => void;
  calendarPlanActive: boolean;
  dayLog?: CalendarDayLog;
  activeActions: PlannerAction[];
  riskRows?: MethodRiskRow[];
  onUpdateDayLog: (patch: Partial<CalendarDayLog>) => void;
};

export function DayDetailPanel({
  iso,
  onClose,
  estimate,
  plannerMeta,
  isBleeding,
  hasCredit,
  onToggleCredit,
  onMarkPeriodStart,
  onMarkPeriodEnd,
  calendarPlanActive,
  dayLog,
  activeActions,
  riskRows,
  onUpdateDayLog,
}: Props) {
  const [addingEvent, setAddingEvent] = useState<AddEventKind | null>(null);
  const [ecType, setEcType] = useState<EcType>("levonorgestrel");
  const [ecHours, setEcHours] = useState<string>("");
  const [confirmClearAction, setConfirmClearAction] = useState(false);

  if (!iso) return null;

  const oc = plannerMeta?.overrideCost;
  const events = dayLog?.events ?? [];
  const hasPlanB = events.some((e) => e.kind === "plan_b_taken");
  const logActions = (["U", "W", "C", "A"] as PlannerAction[]).filter((action) =>
    action === "U" || action === "A" || activeActions.includes(action),
  );

  function commitEvent() {
    if (!iso || !addingEvent) return;
    const baseEvent = {
      id: createId(),
      occurredAt: timestampOnSelectedDate(iso),
    };
    let newEvent: DayEvent;
    if (addingEvent === "plan_b_taken") {
      const parsedHours = ecHours.trim() ? Number(ecHours) : undefined;
      const hours = parsedHours == null || !Number.isFinite(parsedHours)
        ? undefined
        : Math.max(0, Math.min(120, parsedHours));
      newEvent = {
        ...baseEvent,
        kind: "plan_b_taken",
        ecType,
        hoursFromAct: hours,
      };
    } else {
      newEvent = { ...baseEvent, kind: addingEvent };
    }
    onUpdateDayLog({ events: [...events, newEvent] });
    setAddingEvent(null);
    setEcHours("");
  }

  function removeEvent(id: string) {
    onUpdateDayLog({ events: events.filter((e) => e.id !== id) });
  }

  return (
    <div className="day-panel-backdrop" role="presentation" onMouseDown={onClose}>
    <div
      className="day-panel card"
      role="dialog"
      aria-modal="true"
      aria-labelledby="day-panel-title"
      onMouseDown={(event) => event.stopPropagation()}
    >
      <div className="day-panel-head">
        <div>
          <span className="eyebrow">Day details</span>
          <h3 id="day-panel-title">{new Date(`${iso}T12:00:00`).toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric" })}</h3>
        </div>
        <button type="button" className="icon-button" aria-label="Close day details" onClick={onClose}>
          <X aria-hidden />
        </button>
      </div>
      {estimate?.usingSampleCycle && (
        <p className="warn compact">Sample cycle preview — log period bleeding for a personal estimate.</p>
      )}
      <div className="day-chip-row" aria-label="Day status">
        <span className="day-status-chip">{estimate?.phase ?? "Unknown phase"}</span>
        {estimate?.cycleDay != null && (
          <span className="day-status-chip">Cycle day {estimate.cycleDay}</span>
        )}
        {isBleeding && <span className="day-status-chip day-status-period">Period</span>}
      </div>
      {calendarPlanActive && plannerMeta && (
        <section className={`day-recommendation-card action-card-${plannerMeta.recommendedAction}`}>
          <span>Recommendation</span>
          <strong>{plannerMeta.recommendedAction}</strong>
          <div className="risk-score-row">
            <span>Risk score</span>
            <div className="risk-score-meter" aria-hidden>
              <span style={{ width: `${Math.max(0, Math.min(100, plannerMeta.rawRiskScore))}%` }} />
            </div>
            <b>{plannerMeta.rawRiskScore}/100</b>
          </div>
        </section>
      )}
      {calendarPlanActive && !plannerMeta && (
        <p className="meta">No planner recommendation for this date. Recompute the plan or extend calendar cycles.</p>
      )}
      {calendarPlanActive && oc && (oc.condoms > 0 || oc.abstinenceDays > 0 || oc.note) && (
        <section className="override-card">
          <span>If you use less protection</span>
          <p>{oc.note || "The planner may need recovery days to stay on target."}</p>
          <div className="override-counts">
            {oc.condoms > 0 && <strong>+{oc.condoms} protected day(s)</strong>}
            {oc.abstinenceDays > 0 && <strong>+{oc.abstinenceDays} abstinence day(s)</strong>}
          </div>
        </section>
      )}
      {calendarPlanActive && riskRows?.length ? (
        <section className="method-risk-card" aria-label="Method risk estimate">
          <div>
            <h4>Method risk estimate</h4>
            <p className="hint compact">
              Percent conception risk for this day. “Expected day” is averaged across ovulation
              uncertainty and your planned frequency; “single act” is one act on this date.
              The 2-SD range and peak are conditional what-ifs.
            </p>
          </div>
          <div className="method-risk-list">
            {riskRows.map((row) => (
              <div key={row.action} className={`method-risk-row method-risk-${row.action}`}>
                <strong>{row.action}</strong>
                <span>
                  Expected day
                  <b>{formatRiskPercent(row.expectedDayPattern)}</b>
                </span>
                <span>
                  Single act avg
                  <b>{formatRiskPercent(row.expectedSingleAct)}</b>
                </span>
                <span>
                  2-SD act range
                  <b>{formatRiskPercent(row.plausibleLow)}–{formatRiskPercent(row.plausibleHigh)}</b>
                </span>
                <span>
                  Peak aligned
                  <b>{formatRiskPercent(row.peakAligned)}</b>
                </span>
              </div>
            ))}
          </div>
        </section>
      ) : null}
      <p className="hint compact">{estimate?.notes}</p>

      <section className="day-log-section">
        <h4>Log what happened</h4>
        <div className="action-log-row" role="group" aria-label="Actual action">
          {logActions.map((action) => (
            <button
              key={action}
              type="button"
              className={`action-log action-log-${action}`}
              aria-pressed={dayLog?.actualAction === action}
              onClick={() => {
                if (dayLog?.actualAction === action) {
                  setConfirmClearAction(true);
                  return;
                }
                setConfirmClearAction(false);
                onUpdateDayLog({ actualAction: action, reconciled: true });
              }}
            >
              <strong>{action}</strong>
              <span>{ACTION_LABEL[action]}</span>
            </button>
          ))}
        </div>
        {confirmClearAction && (
          <div className="clear-action-confirm">
            <span>Clear logged action for this day?</span>
            <button
              type="button"
              className="ghost"
              onClick={() => {
                onUpdateDayLog({ actualAction: undefined, reconciled: undefined });
                setConfirmClearAction(false);
              }}
            >
              Clear
            </button>
            <button type="button" className="ghost" onClick={() => setConfirmClearAction(false)}>
              Cancel
            </button>
          </div>
        )}
        <p className="hint compact">
          This sets the day's overall pattern. For a one-off incident — a broken
          condom or unprotected sex on a day you'd planned to abstain — add an
          Event below instead; incidents are counted per act, so they carry more
          weight than a whole-day pattern.
        </p>
        <label>
          Notes
          <textarea
            rows={2}
            value={dayLog?.notes ?? ""}
            placeholder="Optional note"
            onChange={(event) => onUpdateDayLog({ notes: event.target.value || undefined })}
          />
        </label>

        <div className="day-events" aria-label="Events">
          <div className="day-events-head">
            <h5>Events</h5>
            <span className="hint">
              Discrete incidents — broken condom, unplanned unprotected sex, Plan
              B — logged per act, on any day regardless of the plan.
            </span>
          </div>
          {events.length > 0 && (
            <ul className="day-events-list">
              {events.map((event) => (
                <li key={event.id}>
                  <div>
                    <strong>{describeEvent(event)}</strong>
                    <span className="hint compact">{formatEventTime(event.occurredAt)}</span>
                  </div>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`Remove ${describeEvent(event)}`}
                    onClick={() => removeEvent(event.id)}
                  >
                    <X aria-hidden />
                  </button>
                </li>
              ))}
            </ul>
          )}
          {addingEvent === null ? (
            <div className="day-events-add">
              <button type="button" className="ghost" onClick={() => setAddingEvent("condom_broke")}>
                + Condom broke
              </button>
              <button
                type="button"
                className="ghost"
                onClick={() => setAddingEvent("unplanned_unprotected")}
              >
                + Unplanned unprotected
              </button>
              <button type="button" className="ghost" onClick={() => setAddingEvent("plan_b_taken")}>
                + Emergency contraception
              </button>
            </div>
          ) : (
            <div className="day-events-form">
              <strong>{EVENT_LABEL[addingEvent]}</strong>
              {addingEvent === "plan_b_taken" && (
                <>
                  <label>
                    Type
                    <select value={ecType} onChange={(e) => setEcType(e.target.value as EcType)}>
                      {(Object.keys(EC_LABEL) as EcType[]).map((kind) => (
                        <option key={kind} value={kind}>
                          {EC_LABEL[kind]}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Hours since the act — needed for the dose to affect the estimate
                    <input
                      type="number"
                      min={0}
                      max={120}
                      step={0.5}
                      value={ecHours}
                      placeholder="e.g. 12"
                      onChange={(e) => setEcHours(e.target.value)}
                    />
                  </label>
                </>
              )}
              <div className="day-events-form-actions">
                <button type="button" onClick={commitEvent}>
                  Save event
                </button>
                <button
                  type="button"
                  className="ghost"
                  onClick={() => {
                    setAddingEvent(null);
                    setEcHours("");
                  }}
                >
                  Cancel
                </button>
              </div>
            </div>
          )}
          {hasPlanB && (
            <div className="ec-cycle-effects" role="note">
              <strong>{EC_CYCLE_EFFECTS_COPY.heading}</strong>
              <ul>
                {EC_CYCLE_EFFECTS_COPY.points.map((point) => (
                  <li key={point}>{point}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      </section>

      <details className="body-signals">
        <summary>Body signals <span>Optional</span></summary>
        <div className="body-signal-grid">
          <label>
            Cervical mucus
            <select
              value={dayLog?.mucus ?? ""}
              onChange={(event) => onUpdateDayLog({ mucus: (event.target.value || undefined) as CalendarDayLog["mucus"] })}
            >
              <option value="">Not logged</option>
              <option value="dry">Dry</option>
              <option value="sticky">Sticky</option>
              <option value="creamy">Creamy</option>
              <option value="egg-white">Egg-white</option>
            </select>
          </label>
          <label>
            Ovulation test (LH)
            <select
              value={dayLog?.opk ?? ""}
              onChange={(event) => onUpdateDayLog({ opk: (event.target.value || undefined) as CalendarDayLog["opk"] })}
            >
              <option value="">Not logged</option>
              <option value="negative">Negative</option>
              <option value="positive">Positive</option>
              <option value="unclear">Unclear</option>
            </select>
          </label>
          <label>
            Basal body temp (°C)
            <input
              type="number"
              min="34"
              max="40"
              step="0.01"
              value={dayLog?.bbtCelsius ?? ""}
              placeholder="36.65"
              onChange={(event) => onUpdateDayLog({ bbtCelsius: event.target.value ? Number(event.target.value) : undefined })}
            />
          </label>
          <label className="signal-check">
            <input
              type="checkbox"
              checked={dayLog?.mittelschmerz ?? false}
              onChange={(event) => onUpdateDayLog({ mittelschmerz: event.target.checked })}
            />
            Ovulation pain
          </label>
          <label className="signal-check">
            <input
              type="checkbox"
              checked={dayLog?.breastTender ?? false}
              onChange={(event) => onUpdateDayLog({ breastTender: event.target.checked })}
            />
            Breast tender
          </label>
        </div>
      </details>

      <div className="day-panel-actions">
        <h4>Period tracking</h4>
        <button type="button" onClick={onMarkPeriodStart}>
          {isBleeding ? "Confirm period started on this day" : "Mark period start"}
        </button>
        <button type="button" className="ghost" onClick={onMarkPeriodEnd}>
          {isBleeding ? "Confirm period ended on this day" : "Last bleeding day was this day"}
        </button>
        <label className="credit-toggle">
          <input type="checkbox" checked={hasCredit} onChange={onToggleCredit} />
          Voluntary abstinence credit (day I could have had sex but abstained)
        </label>
      </div>
      {isBleeding && <p className="hint compact">This date falls in a logged bleeding range.</p>}
    </div>
    </div>
  );
}

const ACTION_LABEL: Record<PlannerAction, string> = {
  U: "Unprotected",
  W: "Withdrawal",
  C: "Protected",
  A: "Abstained",
};

function formatRiskPercent(value: number): string {
  const pct = value * 100;
  if (pct > 0 && pct < 0.01) return "<0.01%";
  if (pct < 1) return `${pct.toFixed(2)}%`;
  return `${pct.toFixed(1)}%`;
}
