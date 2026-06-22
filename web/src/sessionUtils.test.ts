import { describe, expect, it } from "vitest";
import {
  hydratePersistedSession,
  SYNC_EPOCH,
  type PersistedSession,
} from "./sessionUtils";

describe("hydratePersistedSession", () => {
  it("recognizes a used legacy synced session without a configured marker", () => {
    const legacy = {
      plannerOptionsUpdatedAt: "2026-06-22T12:00:00.000Z",
    } as Partial<PersistedSession>;

    expect(hydratePersistedSession(legacy, 2).plannerConfigured).toBe(true);
  });

  it("does not treat untouched legacy defaults as configured", () => {
    const legacy = { plannerOptionsUpdatedAt: SYNC_EPOCH } as Partial<PersistedSession>;

    expect(hydratePersistedSession(legacy, 2).plannerConfigured).toBe(false);
  });

  it("respects an explicit configured marker", () => {
    const current = {
      plannerConfigured: false,
      plannerOptionsUpdatedAt: "2026-06-22T12:00:00.000Z",
    } as Partial<PersistedSession>;

    expect(hydratePersistedSession(current, 2).plannerConfigured).toBe(false);
  });
});
