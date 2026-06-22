/** Cycle-length prediction helpers for calendar-mode planning. */

export function inferCycleLengthsFromStarts(sortedIso: string[]): number[] {
  if (sortedIso.length < 2) return [];
  const out: number[] = [];
  for (let i = 1; i < sortedIso.length; i++) {
    const a = new Date(sortedIso[i - 1] + "T12:00:00");
    const b = new Date(sortedIso[i] + "T12:00:00");
    const days = Math.round((b.getTime() - a.getTime()) / 86_400_000);
    if (days >= 21 && days <= 60) out.push(days);
  }
  return out;
}

/** Mean of recent lengths; fallback 28. */
export function predictNextCycleLength(historyLengths: number[]): number {
  if (historyLengths.length === 0) return 28;
  const mean = historyLengths.reduce((x, y) => x + y, 0) / historyLengths.length;
  return Math.max(21, Math.min(45, Math.round(mean)));
}

export function currentCycleDayFromLastStart(lastStartIso: string, today = new Date()): number {
  const start = new Date(lastStartIso + "T12:00:00");
  const d = Math.floor((today.getTime() - start.getTime()) / 86_400_000) + 1;
  return Math.max(1, d);
}

export type CalendarCycleRow = {
  cycleLengthDays: number;
  cycleSdDays: number;
  actsPerWeek: number;
  ageYears: number;
  bodySignals?: BodySignalInputs;
};

export type CycleLengthPosterior = {
  mean: number;
  predictiveSd: number;
  lower: number;
  upper: number;
  observedCount: number;
};

export type BodySignalInputs = {
  cervicalMucusPeakDay?: number;
  basalBodyTemperatureShiftDay?: number;
  lhSurgeDay?: number;
  wearableTemperatureShiftDay?: number;
};

export function buildCalendarCycles(
  count: number,
  ageYears: number,
  actsPerWeek: number,
  cycleSdDays: number,
  historyLengths: number[],
): CalendarCycleRow[] {
  const posterior = cycleLengthPosterior(historyLengths, ageYears);
  const lengths = predictCycleLengthsV15(count, historyLengths, ageYears);
  const baseSd = cycleSdDays;
  const widen = sdWidenFromVariance(historyLengths);
  const rowSd = Math.min(10, baseSd + widen + posterior.predictiveSd * 0.35);
  return lengths.map((cycleLengthDays) => ({
    cycleLengthDays,
    cycleSdDays: rowSd,
    actsPerWeek,
    ageYears,
  }));
}

function interpolateAgeAnchors(age: number, anchors: Array<[number, number]>): number {
  if (age <= anchors[0][0]) return anchors[0][1];
  for (let i = 0; i < anchors.length - 1; i++) {
    const [a0, v0] = anchors[i];
    const [a1, v1] = anchors[i + 1];
    if (age <= a1) {
      const t = Math.max(0, Math.min(1, (age - a0) / (a1 - a0)));
      return v0 + t * (v1 - v0);
    }
  }
  return anchors[anchors.length - 1][1];
}

/** Mirrors `reference_cycle_length_for_age` in planner-core. */
export function referenceCycleLengthForAge(age: number): number {
  return interpolateAgeAnchors(age, [
    [18, 30],
    [24, 29],
    [29, 28.5],
    [34, 28],
    [40, 27.8],
    [44, 28],
    [48, 34],
    [50, 40],
    [55, 55],
  ]);
}

export function trimmedMeanLength(lengths: number[], trimEachSide = 0): number {
  if (lengths.length === 0) return 28;
  const s = [...lengths].sort((a, b) => a - b);
  const lo = Math.min(trimEachSide, Math.floor(s.length / 4));
  const hi = s.length - lo;
  const slice = s.slice(lo, hi);
  if (slice.length === 0) return s[Math.floor(s.length / 2)];
  return slice.reduce((a, b) => a + b, 0) / slice.length;
}

export function sampleStdDev(values: number[]): number {
  if (values.length < 2) return 0;
  const m = values.reduce((a, b) => a + b, 0) / values.length;
  const v = values.reduce((acc, x) => acc + (x - m) ** 2, 0) / (values.length - 1);
  return Math.sqrt(v);
}

/** Extra days to add to ovulation SD when lengths are noisy (capped by caller). */
export function sdWidenFromVariance(historyLengths: number[]): number {
  const sd = sampleStdDev(historyLengths);
  if (sd < 1.5) return 0;
  return Math.min(4, (sd - 1.5) * 0.6);
}

export function cycleLengthPosterior(
  historyLengths: number[],
  ageYears: number,
): CycleLengthPosterior {
  const priorMean = referenceCycleLengthForAge(ageYears);
  const priorSd = 4;
  if (historyLengths.length === 0) {
    const lower = Math.max(21, Math.round(priorMean - 1.28 * priorSd));
    const upper = Math.min(60, Math.round(priorMean + 1.28 * priorSd));
    return {
      mean: priorMean,
      predictiveSd: priorSd,
      lower,
      upper,
      observedCount: 0,
    };
  }

  const trimmedMean = trimmedMeanLength(historyLengths, historyLengths.length >= 5 ? 1 : 0);
  const empiricalSd = Math.max(1.75, sampleStdDev(historyLengths) || 2.5);
  const priorVar = priorSd ** 2;
  const obsVar = empiricalSd ** 2;
  const n = historyLengths.length;
  const posteriorPrecision = 1 / priorVar + n / obsVar;
  const posteriorMean = ((priorMean / priorVar) + (n * trimmedMean) / obsVar) / posteriorPrecision;
  const posteriorVar = 1 / posteriorPrecision;
  const predictiveSd = Math.sqrt(posteriorVar + obsVar);
  const lower = Math.max(21, Math.round(posteriorMean - 1.28 * predictiveSd));
  const upper = Math.min(60, Math.round(posteriorMean + 1.28 * predictiveSd));
  return {
    mean: posteriorMean,
    predictiveSd,
    lower,
    upper,
    observedCount: n,
  };
}

export function blendedCycleLength(personalMean: number, ageYears: number, nObserved: number): number {
  const ref = referenceCycleLengthForAge(ageYears);
  const w = Math.min(1, nObserved / 6);
  const b = w * personalMean + (1 - w) * ref;
  return Math.max(21, Math.min(60, Math.round(b)));
}

export function predictCycleLengthsV15(
  count: number,
  historyLengths: number[],
  ageYears: number,
): number[] {
  if (count <= 0) return [];
  const posterior = cycleLengthPosterior(historyLengths, ageYears);
  const n = historyLengths.length;
  const L = blendedCycleLength(posterior.mean, ageYears, Math.max(n, 1));
  return Array.from({ length: count }, () => L);
}

/** @deprecated use predictCycleLengthsV15 via buildCalendarCycles */
export function predictCycleLengths(count: number, historyLengths: number[]): number[] {
  return predictCycleLengthsV15(count, historyLengths, 34);
}
