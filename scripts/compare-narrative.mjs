/**
 * Parity: README/reference JS vs Rust core (narrative scenario, perfect condoms).
 * Run from repo root: node scripts/compare-narrative.mjs
 */
import { execFileSync } from "child_process";
import { fileURLToPath } from "url";
import { dirname, join } from "path";
import { fertilityRiskPlanner } from "./reference-planner.mjs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");

const js = fertilityRiskPlanner({
  ageYears: 34,
  horizonYears: 20,
  targetCumulativeFailure: 0.05,
  actsPerWeek: 3.5,
  condomMode: "perfect",
});

const rustJson = execFileSync(
  "cargo",
  ["run", "-p", "planner-core", "--example", "narrative_json", "--quiet"],
  { cwd: root, encoding: "utf8" }
);
const rust = JSON.parse(rustJson.trim());

const targetCumulativeFailure = 0.05;
const comparisons = [
  // The simplified JS reference intentionally omits Rust's reserve/relaxation
  // post-processing, so exact achieved risk is no longer a parity boundary.
  [
    "achievedCumulativeRisk within target",
    js.achievedCumulativeRisk,
    rust.achievedCumulativeRisk,
    (a, b) =>
      a <= targetCumulativeFailure + 1e-12 &&
      b <= targetCumulativeFailure + 1e-12,
  ],
  ["targetMet", js.targetMet, rust.targetMet, (a, b) => a === b],
  [
    "selectedCondomResidual",
    js.selectedCondomResidual,
    rust.selectedCondomResidual,
    (a, b) => Math.abs(a - b) < 1e-12,
  ],
  ["year0Plan", js.year0Plan, rust.year0Plan, (a, b) => a === b],
];

let ok = true;
for (const [label, j, r, same] of comparisons) {
  const match = same(j, r);
  if (!match) ok = false;
  console.log(`${match ? "OK" : "MISMATCH"} ${label}: js=${JSON.stringify(j)} rust=${JSON.stringify(r)}`);
}

if (!ok) {
  console.error("\nJS counts (year 0): derive from plan", js.year0Plan?.length);
  process.exit(1);
}
console.log("\nJS year0Counts (approx): count C in plan", (js.year0Plan.match(/C/g) || []).length, "A", (js.year0Plan.match(/A/g) || []).length);
console.log("Rust year0Counts:", rust.year0Counts);
