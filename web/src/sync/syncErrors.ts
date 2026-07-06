export function isSyncSnapshotMissing(error: unknown): boolean {
  if (!(error instanceof Error)) return false;
  const message = error.message.toLowerCase();
  return (
    message.includes("not found") ||
    message.includes("no snapshot") ||
    message.includes("could not find")
  );
}
