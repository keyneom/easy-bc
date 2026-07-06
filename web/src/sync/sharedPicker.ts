import { GoogleDriveFolderPicker } from "@keyneom/sync-kit/stores/google-drive/picker";
import type { Authorization } from "@keyneom/sync-kit/core";

export function createEasyBcFolderPicker(): GoogleDriveFolderPicker | null {
  const developerKey = import.meta.env.VITE_GOOGLE_API_KEY?.trim() ?? "";
  const cloudProjectNumber =
    import.meta.env.VITE_GOOGLE_CLOUD_PROJECT_NUMBER?.trim() ?? "";
  if (!developerKey || !cloudProjectNumber) return null;
  return new GoogleDriveFolderPicker({
    developerKey,
    cloudProjectNumber,
    title: "Select the shared EasyBC folder",
  });
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
