import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  chooseWebStorageMode,
  eraseEasyBcBrowserData,
  noteSensitiveWebSession,
  prepareWebPrivacySession,
  webStorageMode,
} from "./webPrivacy";

class MemoryStorage {
  private values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
  clear() { this.values.clear(); }
  key(index: number) { return [...this.values.keys()][index] ?? null; }
  get length() { return this.values.size; }
}

const deleted: string[] = [];

beforeEach(() => {
  deleted.length = 0;
  vi.stubGlobal("localStorage", new MemoryStorage());
  vi.stubGlobal("sessionStorage", new MemoryStorage());
  vi.stubGlobal("indexedDB", {
    databases: async () => [{ name: "easy-bc-extra", version: 1 }],
    deleteDatabase: (name: string) => {
      deleted.push(name);
      const request: Record<string, ((event?: Event) => void) | null> = {
        onsuccess: null,
        onerror: null,
        onblocked: null,
      };
      queueMicrotask(() => request.onsuccess?.());
      return request;
    },
  });
});

describe("web privacy session", () => {
  it("marks untrusted plaintext sessions for cleanup", () => {
    noteSensitiveWebSession();
    expect(localStorage.getItem("easy-bc-temporary-session-dirty")).toBe("1");
    expect(sessionStorage.getItem("easy-bc-temporary-session-active")).toBe("1");
  });

  it("trusted mode disables temporary cleanup markers", () => {
    noteSensitiveWebSession();
    chooseWebStorageMode("trusted");
    expect(webStorageMode()).toBe("trusted");
    expect(localStorage.getItem("easy-bc-temporary-session-dirty")).toBeNull();
    expect(sessionStorage.getItem("easy-bc-temporary-session-active")).toBeNull();
  });

  it("cleans an unfinished temporary session before launch", async () => {
    chooseWebStorageMode("temporary");
    sessionStorage.removeItem("easy-bc-temporary-session-active");
    await prepareWebPrivacySession();
    expect(deleted).toEqual(expect.arrayContaining([
      "easy-bc",
      "easy-bc-sync-kit-auth",
      "easy-bc-sharing",
      "easy-bc-extra",
    ]));
    expect(webStorageMode()).toBe("temporary");
  });

  it("explicit erase also forgets the browser trust choice", async () => {
    chooseWebStorageMode("trusted");
    await eraseEasyBcBrowserData();
    expect(webStorageMode()).toBeNull();
  });
});
