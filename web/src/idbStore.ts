/** Minimal IndexedDB key-value store for period + session data. */

export const KV_PERIOD_STARTS = "periodStarts";
export const KV_PERIOD_RECORDS = "periodRecords";
export const KV_SESSION = "session";

const DB_NAME = "easy-bc";
const DB_VERSION = 1;
const STORE = "kv";

function openDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onerror = () => reject(req.error);
    req.onsuccess = () => resolve(req.result);
    req.onupgradeneeded = () => {
      req.result.createObjectStore(STORE);
    };
  });
}

export async function idbGet<T>(key: string): Promise<T | undefined> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const req = tx.objectStore(STORE).get(key);
    req.onerror = () => reject(req.error);
    req.onsuccess = () => resolve(req.result as T | undefined);
    tx.oncomplete = () => db.close();
  });
}

export async function idbSet<T>(key: string, value: T): Promise<void> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    tx.objectStore(STORE).put(value, key);
    tx.onerror = () => reject(tx.error);
    tx.oncomplete = () => {
      db.close();
      resolve();
    };
  });
}

/** One-time migration from legacy localStorage period key. */
const LEGACY_PERIOD_KEY = "easy-bc-period-starts";

export async function migrateLegacyPeriodStartsIfNeeded(): Promise<void> {
  const existing = await idbGet<string[]>(KV_PERIOD_STARTS);
  if (existing && existing.length > 0) return;
  try {
    const raw = localStorage.getItem(LEGACY_PERIOD_KEY);
    if (!raw) return;
    const v = JSON.parse(raw) as unknown;
    if (Array.isArray(v) && v.every((x) => typeof x === "string")) {
      await idbSet(KV_PERIOD_STARTS, v as string[]);
    }
  } catch {
    /* ignore */
  }
}
