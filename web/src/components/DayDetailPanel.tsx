import type { CyclePhaseEstimate } from "../tracker/cyclePhase";
import type { PlannerDayMeta } from "../tracker/plannerWallMeta";
import { X } from "lucide-react";
import type { CalendarDayLog, PlannerAction } from "../sessionUtils";

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
  if (!iso) return null;

  const oc = plannerMeta?.overrideCost;

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
        <label>
          Notes
          <textarea
            rows={2}
            value={dayLog?.notes ?? ""}
            placeholder="Optional note"
            onChange={(event) => onUpdateDayLog({ notes: event.target.value || undefined })}
          />
        </label>
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
