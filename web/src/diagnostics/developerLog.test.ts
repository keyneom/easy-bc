import { describe, expect, it } from "vitest";
import { diagnosticDetails, formatDeveloperLog } from "./developerLog";

describe("developer diagnostics", () => {
  it("formats entries as portable plain text", () => {
    expect(
      formatDeveloperLog([
        {
          timestamp: "2026-07-12T22:00:00Z",
          area: "migration",
          event: "control-read-failed",
          details: { datasetId: "primary", error: "not found" },
        },
      ]),
    ).toBe(
      "2026-07-12T22:00:00Z [migration] control-read-failed datasetId=primary error=not found",
    );
  });

  it("normalizes multiline details and caps their length", () => {
    const details = diagnosticDetails({ error: `first\nsecond ${"x".repeat(600)}` });
    expect(details.error).not.toContain("\n");
    expect(details.error).toHaveLength(500);
  });
});
