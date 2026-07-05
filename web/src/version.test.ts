import { describe, expect, it } from "vitest";
import { compareSemver, isNewerVersion, normalizeVersion } from "./version";

describe("version helpers", () => {
  it("normalizes leading v", () => {
    expect(normalizeVersion("v0.1.25")).toBe("0.1.25");
  });

  it("compares semver tuples", () => {
    expect(compareSemver("0.1.26", "0.1.25")).toBeGreaterThan(0);
    expect(compareSemver("0.1.25", "0.1.25")).toBe(0);
    expect(compareSemver("0.1.24", "0.1.25")).toBeLessThan(0);
    expect(compareSemver("1.0.0", "0.9.9")).toBeGreaterThan(0);
  });

  it("detects newer versions", () => {
    expect(isNewerVersion("0.1.26", "0.1.25")).toBe(true);
    expect(isNewerVersion("0.1.25", "0.1.25")).toBe(false);
  });
});
