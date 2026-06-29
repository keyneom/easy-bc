export type AutoSyncReason = "startup" | "foreground" | "change";

export const FOREGROUND_SYNC_MIN_HIDDEN_MS = 30_000;

/**
 * Tracks automatic-sync reentrancy. A local data change must be replayed after
 * an in-flight sync, but foreground signals caused by OAuth/passkey windows
 * must not queue another sync and create an authorization feedback loop.
 */
export class AutoSyncTriggerState {
  private running = false;
  private changeQueued = false;

  request(reason: AutoSyncReason): boolean {
    if (!this.running) {
      this.running = true;
      return true;
    }
    if (reason === "change") this.changeQueued = true;
    return false;
  }

  finish(): boolean {
    this.running = false;
    const replayChange = this.changeQueued;
    this.changeQueued = false;
    return replayChange;
  }

  reset(): void {
    this.running = false;
    this.changeQueued = false;
  }
}

export function shouldSyncAfterForeground({
  hiddenAt,
  now,
  operationInProgress,
}: {
  hiddenAt: number | null;
  now: number;
  operationInProgress: boolean;
}): boolean {
  return hiddenAt !== null &&
    now - hiddenAt >= FOREGROUND_SYNC_MIN_HIDDEN_MS &&
    !operationInProgress;
}
