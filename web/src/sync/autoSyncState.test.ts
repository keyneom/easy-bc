import { describe, expect, it } from "vitest";
import {
  AutoSyncTriggerState,
  FOREGROUND_SYNC_MIN_HIDDEN_MS,
  shouldSyncAfterForeground,
} from "./autoSyncState";

describe("automatic sync trigger state", () => {
  it("does not replay foreground signals caused by an authorization window", () => {
    const state = new AutoSyncTriggerState();

    expect(state.request("startup")).toBe(true);
    expect(state.request("foreground")).toBe(false);
    expect(state.finish()).toBe(false);
  });

  it("replays a real local change that arrives during an in-flight sync", () => {
    const state = new AutoSyncTriggerState();

    expect(state.request("startup")).toBe(true);
    expect(state.request("change")).toBe(false);
    expect(state.finish()).toBe(true);
    expect(state.request("change")).toBe(true);
  });

  it("ignores short tab switches and any return during a sync operation", () => {
    expect(shouldSyncAfterForeground({
      hiddenAt: 1_000,
      now: 1_000 + FOREGROUND_SYNC_MIN_HIDDEN_MS - 1,
      operationInProgress: false,
    })).toBe(false);
    expect(shouldSyncAfterForeground({
      hiddenAt: 1_000,
      now: 1_000 + FOREGROUND_SYNC_MIN_HIDDEN_MS,
      operationInProgress: true,
    })).toBe(false);
    expect(shouldSyncAfterForeground({
      hiddenAt: 1_000,
      now: 1_000 + FOREGROUND_SYNC_MIN_HIDDEN_MS,
      operationInProgress: false,
    })).toBe(true);
  });
});
