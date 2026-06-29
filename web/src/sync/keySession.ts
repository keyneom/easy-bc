import type { SyncEnvelopeV1 } from "./types";

export const SYNC_KEY_BACKGROUND_GRACE_MS = 15 * 60 * 1_000;

type SessionEntry = {
  identity: string;
  key: CryptoKey;
};

function envelopeIdentity(envelope: SyncEnvelopeV1): string {
  return [
    envelope.rpId,
    envelope.credentialId,
    envelope.kdfSalt,
  ].join("\n");
}

/**
 * Holds a non-extractable WebCrypto content key only while this page is alive.
 * The key is never serialized to sessionStorage, IndexedDB, or another durable
 * browser store.
 */
export class SyncKeySession {
  private entry: SessionEntry | null = null;
  private pending: { identity: string; key: Promise<CryptoKey> } | null = null;
  private backgroundTimer: ReturnType<typeof setTimeout> | null = null;

  get(envelope: SyncEnvelopeV1): CryptoKey | null {
    const identity = envelopeIdentity(envelope);
    if (this.entry?.identity === identity) return this.entry.key;
    if (this.entry) this.clear();
    return null;
  }

  remember(envelope: SyncEnvelopeV1, key: CryptoKey): void {
    this.entry = { identity: envelopeIdentity(envelope), key };
  }

  async getOrUnlock(
    envelope: SyncEnvelopeV1,
    unlock: () => Promise<CryptoKey>,
  ): Promise<CryptoKey> {
    const cached = this.get(envelope);
    if (cached) return cached;

    const identity = envelopeIdentity(envelope);
    if (this.pending?.identity === identity) return this.pending.key;

    const pendingKey = unlock().then((key) => {
      this.entry = { identity, key };
      return key;
    });
    this.pending = { identity, key: pendingKey };
    try {
      return await pendingKey;
    } finally {
      if (this.pending?.key === pendingKey) this.pending = null;
    }
  }

  clear(): void {
    this.entry = null;
    this.pending = null;
    this.cancelBackgroundTimer();
  }

  pageHidden(): void {
    this.cancelBackgroundTimer();
    this.backgroundTimer = setTimeout(
      () => this.clear(),
      SYNC_KEY_BACKGROUND_GRACE_MS,
    );
  }

  pageVisible(): void {
    this.cancelBackgroundTimer();
  }

  private cancelBackgroundTimer(): void {
    if (this.backgroundTimer !== null) {
      clearTimeout(this.backgroundTimer);
      this.backgroundTimer = null;
    }
  }
}

export const syncKeySession = new SyncKeySession();

if (typeof document !== "undefined") {
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") syncKeySession.pageHidden();
    else syncKeySession.pageVisible();
  });
}

if (typeof window !== "undefined") {
  window.addEventListener("pagehide", () => syncKeySession.clear());
}
