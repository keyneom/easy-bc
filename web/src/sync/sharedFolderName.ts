const MAX_DRIVE_NAME_LENGTH = 255;

/** Sanitize a label for Google Drive folder / calendar display names. */
export function sanitizeOwnerLabel(email: string): string {
  return email.trim().toLowerCase().replace(/\s+/g, "");
}

export function easyBcSyncFolderName(ownerEmail: string): string {
  const label = sanitizeOwnerLabel(ownerEmail);
  const name = `EasyBC — ${label}`;
  return name.length <= MAX_DRIVE_NAME_LENGTH
    ? name
    : name.slice(0, MAX_DRIVE_NAME_LENGTH);
}

export function easyBcCalendarDisplayName(ownerEmail: string): string {
  return easyBcSyncFolderName(ownerEmail);
}

export function profileKey(ownerEmail: string, datasetId: string): string {
  return `${sanitizeOwnerLabel(ownerEmail)}/${datasetId}`;
}

export function parseProfileKey(key: string): { ownerEmail: string; datasetId: string } {
  const slash = key.indexOf("/");
  if (slash <= 0) throw new Error("Invalid profile key.");
  return {
    ownerEmail: key.slice(0, slash),
    datasetId: key.slice(slash + 1),
  };
}
