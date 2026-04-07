import type { CyclePhaseEstimate } from "../tracker/cyclePhase";
import type { PlannerDayMeta } from "../tracker/plannerWallMeta";

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
}: Props) {
  if (!iso) return null;

  const oc = plannerMeta?.overrideCost;

  return (
    <div className="day-panel card">
      <div className="day-panel-head">
        <h3>{iso}</h3>
        <button type="button" className="ghost" onClick={onClose}>
          Close
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
  );
}
