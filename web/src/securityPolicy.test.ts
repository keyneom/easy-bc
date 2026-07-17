import { describe, expect, it } from "vitest";
import html from "../index.html?raw";

describe("web security policy", () => {
  it("allows only the observed Google Identity Services inline style", () => {
    expect(html).toContain(
      "style-src 'self' 'sha256-CJ02OVqT7p9v9HDCMKiouj0TJ0ooW7ybXUHymIEqyeE=' " +
        "https://accounts.google.com/gsi/style",
    );
    expect(html).not.toContain("style-src 'self' 'unsafe-inline'");
  });
});
