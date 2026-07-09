import { describe, expect, it } from "vitest";
import { shouldOpenSyncSettings } from "./sharedRoute";

describe("shouldOpenSyncSettings", () => {
  it.each([
    "?sk-inv=invitation",
    "?sk-resp=1&sk-kr=response",
    "?sk-inv=invitation&grant-files=1",
    "?grant-folder=1",
  ])("routes sharing URL %s to Settings", (search) => {
    expect(shouldOpenSyncSettings(search)).toBe(true);
  });

  it("leaves ordinary app URLs on Calendar", () => {
    expect(shouldOpenSyncSettings("?month=2026-07")).toBe(false);
  });
});
