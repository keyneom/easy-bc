import { afterEach, describe, expect, it, vi } from "vitest";
import { SYNC_KEY_BACKGROUND_GRACE_MS, SyncKeySession } from "./keySession";
import type { SyncEnvelopeV1 } from "./types";

function envelope(overrides: Partial<SyncEnvelopeV1> = {}): SyncEnvelopeV1 {
  return {
    schemaVersion: 1,
    algorithm: "AES-256-GCM+HKDF-SHA-256",
    credentialId: "credential",
    rpId: "keyneom.github.io",
    prfInput: "input",
    kdfSalt: "salt",
    nonce: "nonce",
    ciphertext: "ciphertext",
    updatedAt: "2026-06-29T00:00:00.000Z",
    ...overrides,
  };
}

const key = (name: string) => ({ name }) as unknown as CryptoKey;

describe("in-memory sync key session", () => {
  afterEach(() => vi.useRealTimers());

  it("reuses only the key matching the encrypted snapshot identity", () => {
    const session = new SyncKeySession();
    const stored = key("stored");
    session.remember(envelope(), stored);

    expect(session.get(envelope())).toBe(stored);
    expect(session.get(envelope({ credentialId: "replacement" }))).toBeNull();
    expect(session.get(envelope())).toBeNull();
  });

  it("coalesces concurrent passkey unlocks", async () => {
    const session = new SyncKeySession();
    const stored = key("stored");
    const unlock = vi.fn(async () => stored);

    const [first, second] = await Promise.all([
      session.getOrUnlock(envelope(), unlock),
      session.getOrUnlock(envelope(), unlock),
    ]);

    expect(first).toBe(stored);
    expect(second).toBe(stored);
    expect(unlock).toHaveBeenCalledTimes(1);
  });

  it("clears the key after the tab remains hidden beyond the grace period", () => {
    vi.useFakeTimers();
    const session = new SyncKeySession();
    session.remember(envelope(), key("stored"));

    session.pageHidden();
    vi.advanceTimersByTime(SYNC_KEY_BACKGROUND_GRACE_MS - 1);
    expect(session.get(envelope())).not.toBeNull();

    session.pageHidden();
    vi.advanceTimersByTime(SYNC_KEY_BACKGROUND_GRACE_MS);
    expect(session.get(envelope())).toBeNull();
  });

  it("keeps the key when the tab returns before the grace period", () => {
    vi.useFakeTimers();
    const session = new SyncKeySession();
    const stored = key("stored");
    session.remember(envelope(), stored);

    session.pageHidden();
    vi.advanceTimersByTime(SYNC_KEY_BACKGROUND_GRACE_MS - 1);
    session.pageVisible();
    vi.advanceTimersByTime(SYNC_KEY_BACKGROUND_GRACE_MS);

    expect(session.get(envelope())).toBe(stored);
  });

  it("clears associated in-memory authorization when the key session locks", () => {
    const onClear = vi.fn();
    const session = new SyncKeySession(onClear);
    session.remember(envelope(), key("stored"));

    session.clear();

    expect(onClear).toHaveBeenCalledTimes(1);
  });
});
