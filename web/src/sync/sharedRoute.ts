export function shouldOpenSyncSettings(search: string): boolean {
  const params = new URLSearchParams(search.startsWith("?") ? search.slice(1) : search);
  return (
    params.has("sk-inv") ||
    params.get("sk-resp") === "1" ||
    params.get("grant-files") === "1" ||
    params.get("grant-folder") === "1"
  );
}
