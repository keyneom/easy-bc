import { useCallback, useSyncExternalStore } from "react";

/**
 * Theme mode: "system" follows the OS via prefers-color-scheme (no
 * data-theme attribute); explicit "light"/"dark" stamp data-theme on <html>,
 * which tokens.css treats as overriding the media query in both directions.
 */
export type ThemeMode = "system" | "light" | "dark";

const STORAGE_KEY = "easybc.themeMode";
const listeners = new Set<() => void>();

export function storedThemeMode(): ThemeMode {
  if (typeof window === "undefined") return "system";
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    return raw === "light" || raw === "dark" ? raw : "system";
  } catch {
    return "system";
  }
}

export function applyThemeMode(mode: ThemeMode): void {
  if (typeof document === "undefined") return;
  const root = document.documentElement;
  if (mode === "system") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", mode);
}

export function setThemeMode(mode: ThemeMode): void {
  try {
    if (mode === "system") window.localStorage.removeItem(STORAGE_KEY);
    else window.localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    // Private browsing: theme still applies for this session.
  }
  applyThemeMode(mode);
  listeners.forEach((notify) => notify());
}

/** Call once at startup, before first paint, to honor a saved choice. */
export function initThemeMode(): void {
  applyThemeMode(storedThemeMode());
}

function subscribe(onChange: () => void): () => void {
  listeners.add(onChange);
  return () => listeners.delete(onChange);
}

export function useThemeMode(): [ThemeMode, (mode: ThemeMode) => void] {
  const mode = useSyncExternalStore(subscribe, storedThemeMode, () => "system" as ThemeMode);
  const set = useCallback((next: ThemeMode) => setThemeMode(next), []);
  return [mode, set];
}
