import { idbDelete, idbGet, idbSet, KV_DEVELOPER_LOG } from "../idbStore";

export type DeveloperLogEntry = {
  timestamp: string;
  area: string;
  event: string;
  details: Record<string, string>;
};

const MAX_ENTRIES = 100;
let pendingWrite: Promise<void> = Promise.resolve();

function clean(value: unknown): string {
  const text = value instanceof Error ? `${value.name}: ${value.message}` : String(value);
  return text.replaceAll(/\s+/g, " ").trim().slice(0, 500);
}

export function diagnosticDetails(
  values: Record<string, unknown>,
): Record<string, string> {
  return Object.fromEntries(
    Object.entries(values)
      .filter(([, value]) => value !== undefined && value !== null)
      .map(([key, value]) => [key, clean(value)]),
  );
}

export async function loadDeveloperLog(): Promise<DeveloperLogEntry[]> {
  return (await idbGet<DeveloperLogEntry[]>(KV_DEVELOPER_LOG)) ?? [];
}

export function appendDeveloperLog(
  area: string,
  event: string,
  details: Record<string, unknown> = {},
): Promise<void> {
  pendingWrite = pendingWrite
    .catch(() => undefined)
    .then(async () => {
      const current = await loadDeveloperLog();
      const next = current.concat({
        timestamp: new Date().toISOString(),
        area,
        event,
        details: diagnosticDetails(details),
      });
      await idbSet(KV_DEVELOPER_LOG, next.slice(-MAX_ENTRIES));
    });
  return pendingWrite;
}

export async function clearDeveloperLog(): Promise<void> {
  await pendingWrite.catch(() => undefined);
  await idbDelete(KV_DEVELOPER_LOG);
}

export function formatDeveloperLog(entries: DeveloperLogEntry[]): string {
  if (entries.length === 0) return "No diagnostic events recorded.";
  return entries
    .map((entry) => {
      const details = Object.entries(entry.details)
        .map(([key, value]) => `${key}=${value}`)
        .join(" ");
      return `${entry.timestamp} [${entry.area}] ${entry.event}${details ? ` ${details}` : ""}`;
    })
    .join("\n");
}
