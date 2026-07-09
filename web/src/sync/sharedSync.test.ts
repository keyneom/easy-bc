import { afterEach, describe, expect, it, vi } from "vitest";
import { assertAcceptedDatasetResults, sharedSyncConfigFromEnv } from "./sharedSync";

describe("sharedSyncConfigFromEnv", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("returns null without a client id", () => {
    vi.stubEnv("VITE_GOOGLE_WEB_CLIENT_ID", "");
    expect(sharedSyncConfigFromEnv("keyneom.github.io")).toBeNull();
  });

  it("builds config when a client id is set", () => {
    vi.stubEnv("VITE_GOOGLE_WEB_CLIENT_ID", "123.apps.googleusercontent.com");
    expect(sharedSyncConfigFromEnv("keyneom.github.io")).toEqual({
      clientId: "123.apps.googleusercontent.com",
      rpId: "keyneom.github.io",
      googleAudience: "123.apps.googleusercontent.com",
      allowedOrigins: [],
    });
  });
});

describe("assertAcceptedDatasetResults", () => {
  it("keeps a pending invite retryable when any dataset failed", () => {
    expect(() =>
      assertAcceptedDatasetResults([
        { datasetId: "primary", status: "failed", error: new Error("Drive write failed") },
      ]),
    ).toThrow(/primary: Drive write failed/);
  });

  it("accepts an all-success result", () => {
    expect(() =>
      assertAcceptedDatasetResults([{ datasetId: "primary", status: "accepted" }]),
    ).not.toThrow();
  });
});
