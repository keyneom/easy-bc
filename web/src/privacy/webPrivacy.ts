export type WebStorageMode = "temporary" | "trusted";

const MODE_KEY = "easy-bc-web-storage-mode";
const DIRTY_KEY = "easy-bc-temporary-session-dirty";
const ACTIVE_KEY = "easy-bc-temporary-session-active";
const LEGACY_PERIOD_KEY = "easy-bc-period-starts";
const DATABASE_NAMES = ["easy-bc", "easy-bc-sync-kit-auth", "easy-bc-sharing"];

export function webStorageMode(): WebStorageMode | null {
  const value = localStorage.getItem(MODE_KEY);
  return value === "temporary" || value === "trusted" ? value : null;
}

/** Mark that plaintext EasyBC data is available in this browser session. */
export function noteSensitiveWebSession(): void {
  if (webStorageMode() === "trusted") return;
  localStorage.setItem(DIRTY_KEY, "1");
  sessionStorage.setItem(ACTIVE_KEY, "1");
}

export function chooseWebStorageMode(mode: WebStorageMode): void {
  localStorage.setItem(MODE_KEY, mode);
  if (mode === "trusted") {
    localStorage.removeItem(DIRTY_KEY);
    sessionStorage.removeItem(ACTIVE_KEY);
  } else {
    noteSensitiveWebSession();
  }
}

function deleteDatabase(name: string): Promise<void> {
  return new Promise((resolve, reject) => {
    const request = indexedDB.deleteDatabase(name);
    request.onsuccess = () => resolve();
    request.onerror = () => reject(request.error ?? new Error(`Could not delete ${name}.`));
    request.onblocked = () => reject(new Error(`Close other EasyBC tabs to erase ${name}.`));
  });
}

async function easyBcDatabaseNames(): Promise<string[]> {
  const names = new Set(DATABASE_NAMES);
  if (typeof indexedDB.databases === "function") {
    for (const database of await indexedDB.databases()) {
      if (database.name?.startsWith("easy-bc")) names.add(database.name);
    }
  }
  return [...names];
}

/** Remove plaintext app state, cached OAuth tokens, and legacy local identity material. */
export async function eraseEasyBcBrowserData(options: { preserveMode?: boolean } = {}): Promise<void> {
  const mode = webStorageMode();
  await Promise.all((await easyBcDatabaseNames()).map(deleteDatabase));
  localStorage.removeItem(LEGACY_PERIOD_KEY);
  localStorage.removeItem(DIRTY_KEY);
  sessionStorage.removeItem(ACTIVE_KEY);
  if (options.preserveMode && mode) localStorage.setItem(MODE_KEY, mode);
  else localStorage.removeItem(MODE_KEY);
}

/**
 * Runs before React reads IndexedDB. A temporary session that did not finish
 * cleanup on page exit is erased here before any sensitive state can render.
 */
export async function prepareWebPrivacySession(): Promise<void> {
  const unfinishedTemporarySession =
    localStorage.getItem(DIRTY_KEY) === "1" && sessionStorage.getItem(ACTIVE_KEY) !== "1";
  if (unfinishedTemporarySession) {
    await eraseEasyBcBrowserData({ preserveMode: true });
  }
}

/** Best effort only; prepareWebPrivacySession is the guaranteed next-launch fallback. */
export function installTemporarySessionExitCleanup(): () => void {
  const cleanup = () => {
    if (webStorageMode() !== "trusted" && localStorage.getItem(DIRTY_KEY) === "1") {
      for (const name of DATABASE_NAMES) indexedDB.deleteDatabase(name);
      localStorage.removeItem(LEGACY_PERIOD_KEY);
    }
  };
  window.addEventListener("pagehide", cleanup);
  return () => window.removeEventListener("pagehide", cleanup);
}
