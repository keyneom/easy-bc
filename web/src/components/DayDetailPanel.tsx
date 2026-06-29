import { useState } from "react";
import type { CyclePhaseEstimate } from "../tracker/cyclePhase";
import type { PlannerDayMeta } from "../tracker/plannerWallMeta";
import { X } from "lucide-react";
import type { CalendarDayLog, DayEvent, EcType, PlannerAction } from "../sessionUtils";
import { EC_CYCLE_EFFECTS_COPY } from "../strings";

type AddEventKind = "condom_broke" | "unplanned_unprotected" | "plan_b_taken";

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
  onUpdateDayLog,
}: Props) {
  const [addingEvent, setAddingEvent] = useState<AddEventKind | null>(null);
  const [ecType, setEcType] = useState<EcType>("levonorgestrel");
  const [ecHours, setEcHours] = useState<string>("");

  if (!iso) return null;

  const oc = plannerMeta?.overrideCost;
  const events = dayLog?.events ?? [];
  const hasPlanB = events.some((e) => e.kind === "plan_b_taken");

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
      <dl className="day-panel-dl">
        <dt>Phase (estimate)</dt>
        <dd>{estimate?.phase ?? "—"}</dd>
        <dt>Cycle day (estimate)</dt>
        <dd>{estimate?.cycleDay ?? "—"}</dd>
        <dt>Next period (guess)</dt>
        <dd>{estimate?.nextPeriodEstimate ?? "—"}</dd>
        {calendarPlanActive && (
          <>
            <dt className="dt-optimizer">Planner recommendation</dt>
            <dd>{plannerMeta?.recommendedAction ?? "— (recompute plan or date outside horizon)"}</dd>
            <dt className="dt-optimizer">Raw risk score (optimizer)</dt>
            <dd>
              {plannerMeta != null
                ? `${plannerMeta.rawRiskScore} (0–100 within that cycle in the model)`
                : "—"}
            </dd>
            <dt className="dt-optimizer">Override cost (approx.)</dt>
            <dd>
              {oc != null
                ? `${oc.condoms} condom-day(s), ${oc.abstinenceDays} abstinence day(s). ${oc.note || ""}`
                : "—"}
            </dd>
          </>
        )}
      </dl>
      <p className="hint compact">{estimate?.notes}</p>

      <section className="day-log-section">
        <h4>Log what happened</h4>
        <div className="action-log-row" role="group" aria-label="Actual action">
          {([
            ["U", "Unprotected"],
            ["W", "Withdrawal"],
            ["C", "Protected"],
            ["A", "Abstained"],
          ] as [PlannerAction, string][]).map(([action, label]) => (
            <button
              key={action}
              type="button"
              className={`action-log action-log-${action}`}
              aria-pressed={dayLog?.actualAction === action}
              onClick={() => onUpdateDayLog({
                actualAction: dayLog?.actualAction === action ? undefined : action,
              })}
            >
              <strong>{action}</strong>
              <span>{label}</span>
            </button>
          ))}
        </div>
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
                    Hours since the act (optional)
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
        <button type="button" onClick={onMarkPeriodStart}>
          Period started this day
        </button>
        <button type="button" className="ghost" onClick={onMarkPeriodEnd}>
          Last bleeding day was this day
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
