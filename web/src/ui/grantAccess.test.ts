import { describe, expect, it } from "vitest";
import { buildReturnToAppLinks, datasetLabel } from "./GrantAccessScreen";

describe("buildReturnToAppLinks", () => {
  const href =
    "https://keyneom.github.io/easy-bc/?sync-kit-join=1&sk-inv=abc&sk-files=def&owner=leslie%40gmail.com&grant-files=1";

  it("strips the grant marker and adds sk-granted for the app to detect", () => {
    const { httpsUrl } = buildReturnToAppLinks(href);
    const params = new URL(httpsUrl).searchParams;
    expect(params.get("grant-files")).toBeNull();
    expect(params.get("sk-granted")).toBe("1");
    expect(params.get("sk-inv")).toBe("abc");
    expect(params.get("owner")).toBe("leslie@gmail.com");
  });

  it("builds an intent:// URI targeting the app package with an https fallback", () => {
    const { intentUri, httpsUrl } = buildReturnToAppLinks(href);
    expect(intentUri.startsWith("intent://keyneom.github.io/easy-bc/")).toBe(true);
    expect(intentUri).toContain("scheme=https");
    expect(intentUri).toContain("package=com.easybc.planner");
    expect(intentUri).toContain(
      `S.browser_fallback_url=${encodeURIComponent(httpsUrl)}`,
    );
    expect(intentUri.endsWith(";end")).toBe(true);
  });

  it("keeps legacy grant-folder links clean too", () => {
    const { httpsUrl } = buildReturnToAppLinks(
      "https://keyneom.github.io/easy-bc/?owner=a%40b.c&grant-folder=1",
    );
    expect(new URL(httpsUrl).searchParams.get("grant-folder")).toBeNull();
  });
});

describe("datasetLabel", () => {
  it("maps known dataset ids to user-facing names", () => {
    expect(datasetLabel("primary")).toBe("Shared profile data");
    expect(datasetLabel("control")).toBe("Sharing coordination file");
    expect(datasetLabel("cycle")).toBe("Cycle & periods");
  });

  it("falls back to the raw id for unknown datasets", () => {
    expect(datasetLabel("custom-thing")).toBe("custom-thing");
  });
});
