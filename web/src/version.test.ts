import { describe, expect, it } from "vitest";
import {
  compareSemver,
  isNewerVersion,
  normalizeVersion,
  publishedVersionUrl,
} from "./version";

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

  it("resolves the GitHub Pages relative base without changing the hostname", () => {
    expect(
      publishedVersionUrl("./", "https://keyneom.github.io/easy-bc/").href,
    ).toBe("https://keyneom.github.io/easy-bc/version.json");
  });

  it("resolves an absolute project base from a nested route", () => {
    expect(
      publishedVersionUrl("/easy-bc/", "https://keyneom.github.io/easy-bc/settings").href,
    ).toBe("https://keyneom.github.io/easy-bc/version.json");
  });
});
