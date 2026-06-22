/**
 * Rust-only JSON fixtures: calendar_cycles, realized_cumulative_risk, initial_action_locks.
 * Run from repo root: node scripts/parity-fixtures.mjs
 */
import { execFileSync } from "child_process";
import { dirname, join } from "path";
import { fileURLToPath } from "url";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

function planFromStdin(json) {
  return JSON.parse(
    execFileSync(
      "cargo",
      ["run", "-p", "planner-core", "--example", "plan_stdin_json", "--quiet"],
      { cwd: root, input: `${JSON.stringify(json)}\n`, encoding: "utf8", maxBuffer: 32 * 1024 * 1024 },
    ).trim(),
  );
}

const calendarCycles = {
  targetCumulativeFailure: 0.05,
  horizonYears: 1,
  condomMode: "perfect",
  calendarCycles: [
    { cycleLengthDays: 26, cycleSdDays: 2.5, actsPerWeek: 3.5, ageYears: 34 },
    { cycleLengthDays: 30, cycleSdDays: 3.0, actsPerWeek: 3.5, ageYears: 34 },
  ],
};

const realized = {
  targetCumulativeFailure: 0.05,
  horizonYears: 5,
  realizedCumulativeRisk: 0.02,
};

const initialLocks = {
  targetCumulativeFailure: 0.15,
  horizonYears: 1,
  initialActionLocks: [{ yearIndex: 0, day: 10, action: "C" }],
};

let ok = true;
for (const [name, payload] of Object.entries({
  calendarCycles,
  realized,
  initialLocks,
})) {
  let out;
  try {
    out = planFromStdin(payload);
  } catch (e) {
    console.error(name, e);
    ok = false;
    continue;
  }
  if (!out.years?.length) {
    console.error("FAIL", name, "no years");
    ok = false;
    continue;
  }
  if (!out.targetMet) {
    console.error("FAIL", name, "target not met");
    ok = false;
    continue;
  }
  console.log("OK", name, "rows", out.years.length);
}

if (!ok) process.exit(1);
console.log("\nparity-fixtures: all OK");
