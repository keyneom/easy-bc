/**
 * Deterministic avatar color: FNV-1a over the profile key selects a hue;
 * saturation/lightness are fixed per theme so every profile is
 * distinguishable without clashing with the brand accent.
 *
 * The Android kit implements the same function (EbKit.avatarHue) — keep the
 * algorithm in sync so a profile renders the same color on every platform.
 */
export function avatarHue(key: string): number {
  let hash = 0x811c9dc5;
  for (let i = 0; i < key.length; i += 1) {
    hash ^= key.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash % 360;
}

export function avatarColor(key: string): string {
  // hsl() keeps lightness readable for white initials in both themes.
  return `hsl(${avatarHue(key)}, 48%, 44%)`;
}

/** 1–2 grapheme initials from a display name ("Leslie T" -> "LT"). */
export function avatarInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "?";
  const first = [...words[0]][0] ?? "?";
  const second = words.length > 1 ? [...words[words.length - 1]][0] ?? "" : "";
  return (first + second).toUpperCase();
}
