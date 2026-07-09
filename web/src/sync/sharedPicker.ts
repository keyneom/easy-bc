import { GoogleDriveFolderPicker } from "@keyneom/sync-kit/stores/google-drive/picker";
import type { Authorization } from "@keyneom/sync-kit/core";
import type { SharingDatasetFileV1 } from "@keyneom/sync-kit/sharing";

export function createEasyBcFolderPicker(title?: string): GoogleDriveFolderPicker | null {
  const developerKey = import.meta.env.VITE_GOOGLE_API_KEY?.trim() ?? "";
  const cloudProjectNumber =
    import.meta.env.VITE_GOOGLE_CLOUD_PROJECT_NUMBER?.trim() ?? "";
  if (!developerKey || !cloudProjectNumber) return null;
  return new GoogleDriveFolderPicker({
    developerKey,
    cloudProjectNumber,
    title: title ?? "Select the shared EasyBC folder",
  });
}

/**
 * Lets the joiner select the shared dataset file(s) so the app is granted
 * `drive.file` read on each. A folder grant doesn't cascade to reading files
 * inside it, so the recipient must pick the files themselves — they appear in
 * the Picker because the owner shared them to this account's email.
 */
export async function pickSharedDatasetFiles(
  authorization: Authorization,
  expectedFiles: SharingDatasetFileV1[] = [],
): Promise<Array<{ fileId: string; name?: string }>> {
  const picker = createEasyBcFolderPicker("Select the shared EasyBC file(s)");
  if (!picker) {
    throw new Error(
      "Google Picker is not configured. Set VITE_GOOGLE_API_KEY and VITE_GOOGLE_CLOUD_PROJECT_NUMBER.",
    );
  }
  document.body.dataset.pickerOpen = "true";
  try {
    const picked = await picker.pickFiles(authorization, { multiSelect: true });
    const selected = picked.map((file) => ({ fileId: file.fileId, name: file.name }));
    validatePickedDatasetFiles(expectedFiles, selected);
    return selected;
  } finally {
    delete document.body.dataset.pickerOpen;
  }
}

export function validatePickedDatasetFiles(
  expectedFiles: Array<{ fileId: string }>,
  selectedFiles: Array<{ fileId: string }>,
): void {
  if (selectedFiles.length === 0) {
    throw new Error("Select the shared EasyBC sync file to continue.");
  }
  const selectedIds = new Set(selectedFiles.map((file) => file.fileId));
  const missing = expectedFiles.filter((file) => !selectedIds.has(file.fileId));
  if (missing.length > 0) {
    throw new Error(
      `Select all ${expectedFiles.length} file${expectedFiles.length === 1 ? "" : "s"} ` +
        "listed in the invitation. The selected files did not match this share.",
    );
  }
}

export async function pickSharedAppFolder(
  authorization: Authorization,
): Promise<{ folderId: string; name?: string } | null> {
  const picker = createEasyBcFolderPicker();
  if (!picker) {
    throw new Error(
      "Google Picker is not configured. Set VITE_GOOGLE_API_KEY and VITE_GOOGLE_CLOUD_PROJECT_NUMBER.",
    );
  }
  // The picker iframe's Select/Cancel footer sits at the bottom of the
  // viewport, where the app's fixed bottom nav would cover it on mobile.
  // Flag the body so CSS can hide the nav for the picker's lifetime.
  document.body.dataset.pickerOpen = "true";
  try {
    const picked = await picker.pickFolder(authorization);
    if (!picked) return null;
    return { folderId: picked.folderId, name: picked.name };
  } finally {
    delete document.body.dataset.pickerOpen;
  }
}
