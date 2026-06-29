import { describe, expect, it } from "vitest";
import {
  hydratePersistedSession,
  SYNC_EPOCH,
  type CalendarDayLog,
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

  it("auto-migrates legacy CB records to actualAction=C + condom_broke event", () => {
    const raw = {
      calendarDayLogs: {
        "2026-06-14": {
          actualAction: "CB",
          updatedAt: "2026-06-14T18:30:00.000Z",
        } as unknown as CalendarDayLog,
      },
    } as Partial<PersistedSession>;
    const hydrated = hydratePersistedSession(raw, 0);
    const migrated = hydrated.calendarDayLogs["2026-06-14"];
    expect(migrated.actualAction).toBe("C");
    expect(migrated.events).toHaveLength(1);
    expect(migrated.events?.[0].kind).toBe("condom_broke");
    expect(migrated.events?.[0].occurredAt).toBe("2026-06-14T18:30:00.000Z");
  });

  it("CB migration is idempotent on already-migrated data", () => {
    const raw = {
      calendarDayLogs: {
        "2026-06-14": {
          actualAction: "C",
          events: [
            {
              id: "migrated:2026-06-14:condom_broke",
              kind: "condom_broke" as const,
              occurredAt: "2026-06-14T18:30:00.000Z",
            },
          ],
        },
      },
    } as Partial<PersistedSession>;
    const hydrated = hydratePersistedSession(raw, 0);
    expect(hydrated.calendarDayLogs["2026-06-14"].events).toHaveLength(1);
  });

  it("leaves non-CB records untouched", () => {
    const raw = {
      calendarDayLogs: {
        "2026-06-14": { actualAction: "U" } as CalendarDayLog,
      },
    } as Partial<PersistedSession>;
    const hydrated = hydratePersistedSession(raw, 0);
    expect(hydrated.calendarDayLogs["2026-06-14"].actualAction).toBe("U");
    expect(hydrated.calendarDayLogs["2026-06-14"].events).toBeUndefined();
  });
});
