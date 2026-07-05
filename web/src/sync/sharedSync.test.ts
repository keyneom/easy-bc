import { afterEach, describe, expect, it, vi } from "vitest";
import { sharedSyncConfigFromEnv } from "./sharedSync";

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
