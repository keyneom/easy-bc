import { afterEach, describe, expect, it, vi } from "vitest";
import {
  clearDriveAccessToken,
  requestDriveAccessToken,
} from "./googleDrive";

type TokenCallback = (response: { access_token?: string; expires_in?: number }) => void;

function installGoogleTokenMock({
  accessToken = "drive-token",
  expiresIn = 3_600,
}: {
  accessToken?: string;
  expiresIn?: number;
} = {}) {
  const requestAccessToken = vi.fn();
  const initTokenClient = vi.fn((config: { callback: TokenCallback }) => {
    requestAccessToken.mockImplementation(() => {
      config.callback({ access_token: accessToken, expires_in: expiresIn });
    });
    return { requestAccessToken };
  });
  vi.stubGlobal("window", {
    google: { accounts: { oauth2: { initTokenClient } } },
  });
  return { initTokenClient, requestAccessToken };
}

describe("Google Drive access-token session", () => {
  afterEach(() => {
    clearDriveAccessToken();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("reuses a valid in-memory token without reopening Google authorization", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-06-29T12:00:00.000Z"));
    const google = installGoogleTokenMock();

    await expect(requestDriveAccessToken("client")).resolves.toBe("drive-token");
    await expect(requestDriveAccessToken("client")).resolves.toBe("drive-token");

    expect(google.initTokenClient).toHaveBeenCalledTimes(1);
    expect(google.requestAccessToken).toHaveBeenCalledTimes(1);
  });

  it("requests a new token after the cached token's safety-adjusted expiry", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-06-29T12:00:00.000Z"));
    const google = installGoogleTokenMock({ expiresIn: 120 });

    await requestDriveAccessToken("client");
    vi.advanceTimersByTime(60_001);
    await requestDriveAccessToken("client");

    expect(google.initTokenClient).toHaveBeenCalledTimes(2);
    expect(google.requestAccessToken).toHaveBeenCalledTimes(2);
  });

  it("clears the token when the app sync session locks", async () => {
    const google = installGoogleTokenMock();

    await requestDriveAccessToken("client");
    clearDriveAccessToken();
    await requestDriveAccessToken("client");

    expect(google.requestAccessToken).toHaveBeenCalledTimes(2);
  });
});
