import { addDaysIso, eachPlannerWallSlot } from "../tracker/calendarMath";
import type { PlannerWallPlan } from "../tracker/plannerWallMeta";

function escapeIcsText(s: string): string {
  return s
    .replace(/\\/g, "\\\\")
    .replace(/\n/g, "\\n")
    .replace(/;/g, "\\;")
    .replace(/,/g, "\\,");
}

/** RFC 5545 line folding (CRLF; continuation lines start with a single space). */
function foldLine(line: string): string {
  const max = 75;
  if (line.length <= max) return line;
  const parts: string[] = [];
  let i = 0;
  while (i < line.length) {
    const take = i === 0 ? max : max - 1;
    const chunk = line.slice(i, i + take);
    parts.push(i === 0 ? chunk : ` ${chunk}`);
    i += chunk.length;
  }
  return parts.join("\r\n");
}

function formatIcsDate(iso: string): string {
  return iso.replace(/-/g, "");
}

function dtStampUtc(): string {
  const d = new Date();
  const y = d.getUTCFullYear();
  const m = String(d.getUTCMonth() + 1).padStart(2, "0");
  const day = String(d.getUTCDate()).padStart(2, "0");
  const h = String(d.getUTCHours()).padStart(2, "0");
  const min = String(d.getUTCMinutes()).padStart(2, "0");
  const s = String(d.getUTCSeconds()).padStart(2, "0");
  return `${y}${m}${day}T${h}${min}${s}Z`;
}

export function buildPlannerIcs(
  plan: PlannerWallPlan,
  anchorIso: string,
  cycleLengths: number[],
  horizonRowCount: number,
): string {
  const slots = eachPlannerWallSlot(anchorIso, cycleLengths, horizonRowCount);
  const lines: string[] = [
    "BEGIN:VCALENDAR",
    "VERSION:2.0",
    "PRODID:-//easy-bc//Planner export//EN",
    "CALSCALE:GREGORIAN",
  ];
  const stamp = dtStampUtc();
  for (const { iso, row, day } of slots) {
    const yr = plan.years[row];
    const dw = yr?.dayWeights[day - 1];
    if (!dw) continue;
    const action = dw.recommendedAction;
    const risk = dw.rawRiskScore;
    const summary = `easy-bc: ${action} · risk ${risk}`;
    const note = dw.overrideCost?.note?.trim() ?? "";
    const descRaw = [
      "easy-bc planner export — not medical advice; not FDA-cleared.",
      `Recommended action: ${action}. Raw risk score (0-100 in-cycle): ${risk}.`,
      `Override-cost note: ${note || "—"}`,
      `Planner row ${row}, cycle day ${day}.`,
    ].join("\n");
    const uid = `easy-bc-${iso}-r${row}-d${day}@local`;
    const start = formatIcsDate(iso);
    const end = formatIcsDate(addDaysIso(iso, 1));
    const props: [string, string][] = [
      ["UID", uid],
      ["DTSTAMP", stamp],
      ["DTSTART;VALUE=DATE", start],
      ["DTEND;VALUE=DATE", end],
      ["SUMMARY", escapeIcsText(summary)],
      ["DESCRIPTION", escapeIcsText(descRaw)],
    ];
    lines.push("BEGIN:VEVENT");
    for (const [k, v] of props) {
      lines.push(foldLine(`${k}:${v}`));
    }
    lines.push("END:VEVENT");
  }
  lines.push("END:VCALENDAR");
  return lines.join("\r\n") + "\r\n";
}

export function downloadIcsFile(filename: string, body: string): void {
  const blob = new Blob([body], { type: "text/calendar;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.rel = "noopener";
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
