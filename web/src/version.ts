export type PublishedVersionInfo = {
  version: string;
  publishedAt?: string;
};

export function normalizeVersion(value: string): string {
  return value.trim().replace(/^v/i, "");
}

/** @returns positive when `left` is newer than `right` */
export function compareSemver(left: string, right: string): number {
  const leftParts = normalizeVersion(left).split(".").map((part) => Number.parseInt(part, 10) || 0);
  const rightParts = normalizeVersion(right).split(".").map((part) => Number.parseInt(part, 10) || 0);
  const length = Math.max(leftParts.length, rightParts.length);
  for (let index = 0; index < length; index += 1) {
    const delta = (leftParts[index] ?? 0) - (rightParts[index] ?? 0);
    if (delta !== 0) return delta;
  }
  return 0;
}

export function isNewerVersion(candidate: string, current: string): boolean {
  return compareSemver(candidate, current) > 0;
}

export function publishedVersionUrl(base: string, pageUrl: string): URL {
  const baseUrl = new URL(base || "./", pageUrl);
  return new URL("version.json", baseUrl);
}

export async function fetchPublishedVersion(): Promise<PublishedVersionInfo | null> {
  if (typeof fetch === "undefined") return null;
  const base = import.meta.env.BASE_URL ?? "/";
  const url = publishedVersionUrl(base, window.location.href);
  url.searchParams.set("t", String(Date.now()));
  const response = await fetch(url, { cache: "no-store" });
  if (!response.ok) return null;
  const data = (await response.json()) as Partial<PublishedVersionInfo>;
  if (!data.version?.trim()) return null;
  return {
    version: normalizeVersion(data.version),
    ...(data.publishedAt ? { publishedAt: data.publishedAt } : {}),
  };
}

export function dismissedUpdateKey(version: string): string {
  return `easy-bc-dismissed-update:${normalizeVersion(version)}`;
}

export function isUpdateDismissed(version: string): boolean {
  try {
    return sessionStorage.getItem(dismissedUpdateKey(version)) === "1";
  } catch {
    return false;
  }
}

export function dismissUpdate(version: string): void {
  try {
    sessionStorage.setItem(dismissedUpdateKey(version), "1");
  } catch {
    /* ignore quota / private mode */
  }
}
