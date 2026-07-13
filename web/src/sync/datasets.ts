import type { SharingRole } from "@keyneom/sync-kit/sharing";
import type { CalendarDayLog, DayEvent } from "../sessionUtils";
import { createEmptySharedSyncPayload } from "./sharedEmptyPayload";
import type { SharedSyncPayloadV1 } from "./sharedTypes";

/*
 * Multi-file dataset split (docs/sync-kit-multi-file-datasets.md).
 *
 * A split profile stores its payload across four encrypted dataset files so
 * the owner can grant each audience exactly the slice they should see:
 *
 *   plan       <base>            planner options & plan outputs
 *   cycle      <base>.cycle      period records + body-signal day-log fields
 *   intimacy   <base>.intimacy   logged acts, incidents, abstinence credits
 *   sensitive  <base>.sensitive  EC journal + EC events
 *
 * Each part file carries a full SharedSyncPayloadV1 shape with only its own
 * sections populated, so the existing codec/merge/fingerprint machinery works
 * per file. `projectDatasetPart` and `combineDatasetParts` are inverses over
 * a full payload (round-trip tested in datasets.test.ts).
 *
 * Legacy profiles (created before the split) keep everything in the single
 * <base> file; ProfileRecord.datasetGrants is absent for them.
 */

export const DATASET_PARTS = ["plan", "cycle", "intimacy", "sensitive"] as const;
export type DatasetPart = (typeof DATASET_PARTS)[number];
export const CONTROL_DATASET_SUFFIX = ".control";

export const DATASET_PART_LABELS: Record<DatasetPart, string> = {
  plan: "Plan & settings",
  cycle: "Cycle & periods",
  intimacy: "Intimacy log",
  sensitive: "Sensitive events",
};

export const DATASET_PART_SUMMARIES: Record<DatasetPart, string> = {
  plan: "Planner options, plan outputs, and profile photo",
  cycle: "Period dates, cycle stats, body signals",
  intimacy: "Logged acts, incidents, abstinence credits",
  sensitive: "Emergency contraception events",
};

/** Dataset ids are slugified to [a-z0-9-], so "." is a safe separator. */
const COMPANION_SEPARATOR = ".";

export type DatasetGrants = Partial<Record<DatasetPart, Exclude<SharingRole, "owner"> | "owner">>;

export function datasetIdForPart(baseDatasetId: string, part: DatasetPart): string {
  return part === "plan" ? baseDatasetId : `${baseDatasetId}${COMPANION_SEPARATOR}${part}`;
}

export function partForDatasetId(baseDatasetId: string, datasetId: string): DatasetPart | null {
  if (datasetId === baseDatasetId) return "plan";
  for (const part of DATASET_PARTS) {
    if (part !== "plan" && datasetId === datasetIdForPart(baseDatasetId, part)) return part;
  }
  return null;
}

/**
 * Base dataset ids are generational: "primary" → "primary.g2" →
 * "primary.g3". A hard-cutover migration cannot reuse the source's id
 * (sync-kit refuses duplicate dataset ids in one folder, and the source
 * must stay readable until the migration closes), so each cutover targets
 * the next generation. The ".g" marker lives in the dot namespace —
 * display-name slugs are [a-z0-9-], so a user profile id can never collide
 * with a generation of another profile (e.g. "emma-2" is a second profile
 * named Emma, never generation 2 of "emma").
 */
export function splitBaseRoot(baseDatasetId: string): string {
  return baseDatasetIdOf(baseDatasetId).replace(/\.g\d+$/, "");
}

function splitBaseGeneration(root: string, baseDatasetId: string): number | null {
  if (baseDatasetId === root) return 1;
  const match = baseDatasetId.match(/^(.*)\.g(\d+)$/);
  if (!match || match[1] !== root) return null;
  return Number(match[2]);
}

/** The next unused generation for a cutover from `sourceBaseId`. */
export function nextSplitBaseId(
  sourceBaseId: string,
  existingDatasetIds: string[],
): string {
  const root = splitBaseRoot(sourceBaseId);
  let max = 1;
  for (const id of existingDatasetIds) {
    const generation = splitBaseGeneration(root, baseDatasetIdOf(id));
    if (generation !== null) max = Math.max(max, generation);
  }
  return `${root}.g${max + 1}`;
}

/**
 * The highest existing generation strictly newer than `currentBaseId`, or
 * null. Non-null means another device already created (or completed) a
 * cutover this device hasn't adopted yet — or that an interrupted cutover
 * on this device should resume into that generation instead of minting a
 * new one.
 */
export function newerSplitBaseId(
  currentBaseId: string,
  existingDatasetIds: string[],
): string | null {
  const root = splitBaseRoot(currentBaseId);
  const current = splitBaseGeneration(root, baseDatasetIdOf(currentBaseId)) ?? 1;
  let best: number | null = null;
  for (const id of existingDatasetIds) {
    const generation = splitBaseGeneration(root, baseDatasetIdOf(id));
    if (generation !== null && generation > current) {
      best = Math.max(best ?? 0, generation);
    }
  }
  return best === null ? null : `${root}.g${best}`;
}

/**
 * Two dataset ids belong to the same profile when their bases share a
 * generation root: "primary", "primary-2.cycle", and "primary-3" are one
 * family. Registry scoping uses this so a migration's target datasets are
 * recorded inside the migrating profile record instead of surfacing as
 * foreign profiles.
 */
export function sameSplitFamily(a: string, b: string): boolean {
  return splitBaseRoot(a) === splitBaseRoot(b);
}

/** "primary.cycle" -> "primary"; plain ids map to themselves. */
export function baseDatasetIdOf(datasetId: string): string {
  for (const part of DATASET_PARTS) {
    if (part === "plan") continue;
    const suffix = `${COMPANION_SEPARATOR}${part}`;
    if (datasetId.endsWith(suffix)) return datasetId.slice(0, -suffix.length);
  }
  return datasetId;
}

export type DiscoveredProfileDatasetGroup = {
  baseDatasetId: string;
  planFileId: string;
  companionFileIds: Partial<Record<Exclude<DatasetPart, "plan">, string>>;
  controlDatasetId?: string;
  controlFileId?: string;
};

/**
 * Reconstruct owned profile groups from the managed dataset files visible to a
 * drive.file token. Hard-cutover generations share one root; only the newest
 * visible generation is returned so an older retained source never reappears
 * as a second profile on a fresh device.
 */
export function discoverProfileDatasetGroups(
  files: ReadonlyArray<{ datasetId: string; fileId: string }>,
): DiscoveredProfileDatasetGroup[] {
  const byBase = new Map<string, DiscoveredProfileDatasetGroup>();
  for (const file of files) {
    const isControl = file.datasetId.endsWith(CONTROL_DATASET_SUFFIX);
    const dataId = isControl
      ? file.datasetId.slice(0, -CONTROL_DATASET_SUFFIX.length)
      : file.datasetId;
    const baseDatasetId = baseDatasetIdOf(dataId);
    const existing = byBase.get(baseDatasetId) ?? {
      baseDatasetId,
      planFileId: "",
      companionFileIds: {},
    };
    if (isControl) {
      existing.controlDatasetId = file.datasetId;
      existing.controlFileId = file.fileId;
    } else {
      const part = partForDatasetId(baseDatasetId, file.datasetId);
      if (part === "plan") existing.planFileId = file.fileId;
      else if (part) existing.companionFileIds[part] = file.fileId;
    }
    byBase.set(baseDatasetId, existing);
  }

  const latestByRoot = new Map<string, DiscoveredProfileDatasetGroup>();
  for (const group of byBase.values()) {
    if (!group.planFileId) continue;
    const root = splitBaseRoot(group.baseDatasetId);
    const current = latestByRoot.get(root);
    if (!current || splitGeneration(group.baseDatasetId) > splitGeneration(current.baseDatasetId)) {
      latestByRoot.set(root, group);
    }
  }
  // Hard cutovers retain the original control dataset id while the data files
  // move to a generated base (for example primary -> primary.g2). Attach that
  // stable control file to the newest visible data generation.
  for (const [root, latest] of latestByRoot) {
    const control = [...byBase.values()].find(
      (group) => splitBaseRoot(group.baseDatasetId) === root && group.controlFileId,
    );
    if (control?.controlFileId) {
      latest.controlDatasetId = control.controlDatasetId;
      latest.controlFileId = control.controlFileId;
    }
  }
  return [...latestByRoot.values()].sort((left, right) => {
    if (splitBaseRoot(left.baseDatasetId) === "primary") return -1;
    if (splitBaseRoot(right.baseDatasetId) === "primary") return 1;
    return left.baseDatasetId < right.baseDatasetId ? -1 : left.baseDatasetId > right.baseDatasetId ? 1 : 0;
  });
}

function splitGeneration(baseDatasetId: string): number {
  const match = baseDatasetId.match(/\.g(\d+)$/);
  return match ? Number(match[1]) : 1;
}

export function grantsFromRequestedGrants(
  requestedGrants: Array<{ datasetId: string; role: SharingRole }>,
): { baseDatasetId: string; grants: DatasetGrants; split: boolean } {
  const baseDatasetId = baseDatasetIdOf(requestedGrants[0]?.datasetId ?? "primary");
  const grants: DatasetGrants = {};
  let sawCompanion = false;
  for (const grant of requestedGrants) {
    const part = partForDatasetId(baseDatasetId, grant.datasetId);
    if (!part) continue;
    if (part !== "plan" || grant.datasetId !== baseDatasetIdOf(grant.datasetId)) sawCompanion = true;
    if (grant.datasetId !== baseDatasetId) sawCompanion = true;
    grants[part] = grant.role as DatasetGrants[DatasetPart];
  }
  // A single grant on the bare base id is the legacy everything-in-one-file
  // share; multiple grants (or any companion id) means a split share.
  const split = sawCompanion || requestedGrants.length > 1;
  return { baseDatasetId, grants, split };
}

export function requestedGrantsFromDatasetGrants(
  baseDatasetId: string,
  grants: DatasetGrants,
): Array<{ datasetId: string; role: Exclude<SharingRole, "owner"> }> {
  const requested: Array<{ datasetId: string; role: Exclude<SharingRole, "owner"> }> = [];
  for (const part of DATASET_PARTS) {
    const role = grants[part];
    if (!role || role === "owner") continue;
    requested.push({ datasetId: datasetIdForPart(baseDatasetId, part), role });
  }
  return requested;
}

export function requestedGrantsWithControl(
  dataGrants: Array<{ datasetId: string; role: Exclude<SharingRole, "owner"> }>,
  controlDatasetId?: string,
  controlFileId?: string,
): Array<{ datasetId: string; role: Exclude<SharingRole, "owner"> }> {
  return [
    ...dataGrants,
    ...(controlDatasetId && controlFileId
      ? [{ datasetId: controlDatasetId, role: "writer" as const }]
      : []),
  ];
}

export const ROLE_RANK: Record<SharingRole, number> = {
  owner: 3,
  admin: 2,
  writer: 1,
  viewer: 0,
};

export function highestGrantedRole(grants: DatasetGrants): SharingRole {
  let best: SharingRole = "viewer";
  for (const part of DATASET_PARTS) {
    const role = grants[part];
    if (role && ROLE_RANK[role] > ROLE_RANK[best]) best = role;
  }
  return best;
}

/** Invite presets — the progressive-disclosure answer to a 4×3 role grid. */
export const SHARING_PRESETS: Array<{
  id: string;
  label: string;
  description: string;
  grants: DatasetGrants;
}> = [
  {
    id: "cycle-only",
    label: "Cycle only",
    description: "They see period dates, cycle stats, and body signals — read-only.",
    grants: { cycle: "viewer" },
  },
  {
    id: "cycle-partner",
    label: "Cycle partner",
    description: "They can log periods and body signals, and see the plan.",
    grants: { cycle: "writer", plan: "viewer" },
  },
  {
    id: "full-partner",
    label: "Full partner",
    description: "They can edit everything except sensitive events.",
    grants: { plan: "writer", cycle: "writer", intimacy: "writer" },
  },
  {
    id: "everything",
    label: "Everything",
    description: "Full edit access, including sensitive events.",
    grants: { plan: "writer", cycle: "writer", intimacy: "writer", sensitive: "writer" },
  },
];

/* ---------- Payload projection & combination ---------- */

function isSensitiveEvent(event: DayEvent): boolean {
  return event.kind === "plan_b_taken";
}

const CYCLE_LOG_FIELDS = [
  "mucus",
  "bbtCelsius",
  "opk",
  "mittelschmerz",
  "breastTender",
] as const;
const INTIMACY_LOG_FIELDS = ["actualAction", "notes", "reconciled"] as const;

export function hasDayLogDataForPart(log: CalendarDayLog, part: DatasetPart): boolean {
  if (part === "cycle") {
    return CYCLE_LOG_FIELDS.some((field) => {
      const value = log[field];
      return typeof value === "boolean" ? value : value !== undefined;
    });
  }
  if (part === "intimacy") {
    return log.actualAction !== undefined || Boolean(log.notes?.trim()) || log.reconciled === true ||
      (log.events ?? []).some((event) => !isSensitiveEvent(event));
  }
  if (part === "sensitive") {
    return (log.events ?? []).some(isSensitiveEvent);
  }
  return false;
}

function latestTimestamp(...values: Array<string | undefined>): string | undefined {
  return values.filter((value): value is string => value !== undefined).sort().at(-1);
}

/**
 * Apply a UI edit while retaining enough information for an emptied split
 * dataset part to publish a deletion tombstone on the next sync.
 */
export function updateCalendarDayLog(
  current: CalendarDayLog | undefined,
  patch: Partial<CalendarDayLog>,
  updatedAt: string,
): CalendarDayLog {
  const next: CalendarDayLog = { ...current, ...patch, updatedAt };
  const affected = new Set<Exclude<DatasetPart, "plan">>();
  if (INTIMACY_LOG_FIELDS.some((field) => Object.prototype.hasOwnProperty.call(patch, field))) {
    affected.add("intimacy");
  }
  if (CYCLE_LOG_FIELDS.some((field) => Object.prototype.hasOwnProperty.call(patch, field))) {
    affected.add("cycle");
  }
  if (Object.prototype.hasOwnProperty.call(patch, "events")) {
    for (const event of [...(current?.events ?? []), ...(next.events ?? [])]) {
      affected.add(isSensitiveEvent(event) ? "sensitive" : "intimacy");
    }
  }

  const deleted = { ...(current?.deletedDatasetParts ?? {}) };
  for (const part of affected) {
    if (hasDayLogDataForPart(next, part)) delete deleted[part];
    else deleted[part] = updatedAt;
  }
  if (Object.keys(deleted).length > 0) next.deletedDatasetParts = deleted;
  else delete next.deletedDatasetParts;
  return next;
}

function projectDayLog(log: CalendarDayLog, part: DatasetPart): CalendarDayLog | null {
  const out: CalendarDayLog = {};
  if (part === "cycle") {
    for (const field of CYCLE_LOG_FIELDS) {
      if (log[field] !== undefined) (out as Record<string, unknown>)[field] = log[field];
    }
  } else if (part === "intimacy") {
    for (const field of INTIMACY_LOG_FIELDS) {
      if (log[field] !== undefined) (out as Record<string, unknown>)[field] = log[field];
    }
    const events = (log.events ?? []).filter((event) => !isSensitiveEvent(event));
    if (events.length > 0) out.events = events;
  } else if (part === "sensitive") {
    const events = (log.events ?? []).filter(isSensitiveEvent);
    if (events.length > 0) out.events = events;
  } else {
    return null;
  }
  const deletedAt = log.deletedDatasetParts?.[part];
  const hasData = hasDayLogDataForPart(out, part);
  if (hasData && (!deletedAt || (log.updatedAt ?? "") > deletedAt)) {
    if (log.updatedAt !== undefined) out.updatedAt = log.updatedAt;
    return out;
  }
  if (deletedAt) return { updatedAt: latestTimestamp(log.updatedAt, deletedAt) };
  const hasAnyData = (["cycle", "intimacy", "sensitive"] as const)
    .some((candidate) => hasDayLogDataForPart(log, candidate));
  if (!hasAnyData && !log.deletedDatasetParts && log.updatedAt) {
    return { updatedAt: log.updatedAt };
  }
  return null;
}

function mergeDayLogSections(
  a: CalendarDayLog | undefined,
  b: CalendarDayLog,
  part: DatasetPart,
): CalendarDayLog {
  const merged: CalendarDayLog = { ...(a ?? {}), ...b };
  const events = [...(a?.events ?? []), ...(b.events ?? [])];
  if (events.length > 0) {
    // Dedupe by id; events from different parts are disjoint by kind.
    const seen = new Set<string>();
    merged.events = events.filter((event) => {
      if (seen.has(event.id)) return false;
      seen.add(event.id);
      return true;
    });
  }
  const updatedAts = [a?.updatedAt, b.updatedAt].filter(
    (value): value is string => value !== undefined,
  );
  if (updatedAts.length > 0) {
    merged.updatedAt = updatedAts.sort().at(-1);
  }
  const deleted = { ...(a?.deletedDatasetParts ?? {}), ...(b.deletedDatasetParts ?? {}) };
  if (part !== "plan") {
    if (hasDayLogDataForPart(b, part)) delete deleted[part];
    else if (b.updatedAt) deleted[part] = b.updatedAt;
  }
  if (Object.keys(deleted).length > 0) merged.deletedDatasetParts = deleted;
  else delete merged.deletedDatasetParts;
  return merged;
}

/**
 * Extract the slice of a full payload that belongs to one dataset part. The
 * result is a full-shape payload with every other section left empty, so the
 * existing per-file codec, merge, and fingerprint logic applies unchanged.
 */
export function projectDatasetPart(
  payload: SharedSyncPayloadV1,
  part: DatasetPart,
): SharedSyncPayloadV1 {
  const empty = createEmptySharedSyncPayload();
  const out: SharedSyncPayloadV1 = {
    ...empty,
    exportedAt: payload.exportedAt,
  };
  if (part === "plan") {
    out.planner = payload.planner;
    if (payload.profileMeta) out.profileMeta = payload.profileMeta;
    return out;
  }
  if (part === "cycle") {
    out.periodRecords = payload.periodRecords;
    out.deletedPeriodStarts = payload.deletedPeriodStarts;
  }
  if (part === "intimacy") {
    out.voluntaryAbstinenceDates = payload.voluntaryAbstinenceDates;
    out.voluntaryAbstinenceUpdatedAt = payload.voluntaryAbstinenceUpdatedAt;
    out.deletedVoluntaryAbstinenceDates = payload.deletedVoluntaryAbstinenceDates;
  }
  if (part === "sensitive") {
    out.ecJournal = payload.ecJournal;
  }
  const logs: Record<string, CalendarDayLog> = {};
  for (const [date, log] of Object.entries(payload.calendarDayLogs)) {
    const projected = projectDayLog(log, part);
    if (projected) logs[date] = projected;
  }
  out.calendarDayLogs = logs;
  return out;
}

/**
 * Reassemble a full payload from the parts this device can decrypt. Missing
 * parts stay at their empty defaults — callers surface that via
 * ProfileRecord.datasetGrants (see restrictedParts in sharedTypes).
 */
export function combineDatasetParts(
  parts: Partial<Record<DatasetPart, SharedSyncPayloadV1>>,
): SharedSyncPayloadV1 {
  const out = createEmptySharedSyncPayload();
  const exportedAts: string[] = [];
  for (const part of DATASET_PARTS) {
    const payload = parts[part];
    if (!payload) continue;
    exportedAts.push(payload.exportedAt);
    if (part === "plan") {
      out.planner = payload.planner;
      if (payload.profileMeta) out.profileMeta = payload.profileMeta;
    }
    if (part === "cycle") {
      out.periodRecords = payload.periodRecords;
      out.deletedPeriodStarts = payload.deletedPeriodStarts;
    }
    if (part === "intimacy") {
      out.voluntaryAbstinenceDates = payload.voluntaryAbstinenceDates;
      out.voluntaryAbstinenceUpdatedAt = payload.voluntaryAbstinenceUpdatedAt;
      out.deletedVoluntaryAbstinenceDates = payload.deletedVoluntaryAbstinenceDates;
    }
    if (part === "sensitive") {
      out.ecJournal = payload.ecJournal;
    }
    for (const [date, log] of Object.entries(payload.calendarDayLogs)) {
      out.calendarDayLogs[date] = mergeDayLogSections(out.calendarDayLogs[date], log, part);
    }
  }
  if (exportedAts.length > 0) {
    out.exportedAt = exportedAts.sort().at(-1)!;
  }
  return out;
}
