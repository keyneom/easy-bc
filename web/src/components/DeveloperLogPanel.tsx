import { useEffect, useMemo, useState } from "react";
import {
  clearDeveloperLog,
  formatDeveloperLog,
  loadDeveloperLog,
  type DeveloperLogEntry,
} from "../diagnostics/developerLog";

export function DeveloperLogPanel() {
  const [entries, setEntries] = useState<DeveloperLogEntry[]>([]);
  const [status, setStatus] = useState<string | null>(null);

  useEffect(() => {
    void loadDeveloperLog().then(setEntries).catch((error: unknown) => {
      setStatus(error instanceof Error ? error.message : String(error));
    });
  }, []);

  const formatted = useMemo(() => formatDeveloperLog(entries), [entries]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(formatted);
      setStatus("Diagnostic log copied.");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : String(error));
    }
  };

  const clear = async () => {
    try {
      await clearDeveloperLog();
      setEntries([]);
      setStatus("Diagnostic log cleared.");
    } catch (error) {
      setStatus(error instanceof Error ? error.message : String(error));
    }
  };

  return (
    <section className="settings-platform-card developer-log-panel">
      <h3>Developer diagnostics</h3>
      <p className="hint">
        Redacted sync and migration decisions stored only in this browser. The log
        excludes access tokens, private keys, and decrypted profile data.
      </p>
      <div className="developer-log-actions">
        <button type="button" className="ghost" onClick={() => void copy()}>
          Copy log
        </button>
        <button type="button" className="ghost" onClick={() => void clear()}>
          Clear log
        </button>
      </div>
      {status && <p className="hint" role="status">{status}</p>}
      <pre>{formatted}</pre>
    </section>
  );
}
