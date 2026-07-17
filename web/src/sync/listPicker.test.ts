import { afterEach, describe, expect, it, vi } from "vitest";
import { pickFilesWithCheckboxes } from "./listPicker";

type PickerCallback = (response: { action?: string; docs?: unknown[] }) => void;

function installPicker(response: { action?: string; docs?: unknown[] }) {
  const dispose = vi.fn();
  const setVisible = vi.fn((_visible: boolean) => callback(response));
  let callback: PickerCallback = () => undefined;

  class DocsView {
    setIncludeFolders() { return this; }
    setMimeTypes() { return this; }
    setSelectFolderEnabled() { return this; }
    setOwnedByMe() { return this; }
    setMode() { return this; }
  }

  class PickerBuilder {
    addView() { return this; }
    enableFeature() { return this; }
    setAppId() { return this; }
    setDeveloperKey() { return this; }
    setOAuthToken() { return this; }
    setOrigin() { return this; }
    setTitle() { return this; }
    setCallback(value: PickerCallback) { callback = value; return this; }
    build() { return { setVisible, dispose }; }
  }

  vi.stubGlobal("window", {
    location: { origin: "https://example.test" },
    google: {
      picker: {
        Action: { PICKED: "picked", CANCEL: "cancel" },
        DocsView,
        DocsViewMode: { LIST: "list", GRID: "grid" },
        PickerBuilder,
        ViewId: { FOLDERS: "folders", DOCS: "docs" },
        Feature: { MULTISELECT_ENABLED: "multi" },
      },
    },
  });

  return { dispose, setVisible };
}

describe("pickFilesWithCheckboxes", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("returns every selected file and filters navigational folders", async () => {
    const picker = installPicker({
      action: "picked",
      docs: [
        { id: "folder", name: "Folder", mimeType: "application/vnd.google-apps.folder" },
        { id: "one", name: "One" },
        { id: "two", name: "Two", url: "https://drive.test/two" },
      ],
    });

    await expect(
      pickFilesWithCheckboxes({
        accessToken: "token",
        developerKey: "developer-key",
        cloudProjectNumber: "123",
        title: "Pick files",
      }),
    ).resolves.toEqual([
      { fileId: "one", name: "One" },
      { fileId: "two", name: "Two", url: "https://drive.test/two" },
    ]);
    expect(picker.setVisible).toHaveBeenCalledWith(true);
    expect(picker.dispose).toHaveBeenCalledOnce();
  });

  it("disposes the picker when the selection contains no file", async () => {
    const picker = installPicker({
      action: "picked",
      docs: [{ id: "folder", mimeType: "application/vnd.google-apps.folder" }],
    });

    await expect(
      pickFilesWithCheckboxes({
        accessToken: "token",
        developerKey: "developer-key",
        cloudProjectNumber: "123",
        title: "Pick files",
      }),
    ).rejects.toThrow(/did not return a Drive file/i);
    expect(picker.dispose).toHaveBeenCalledOnce();
  });
});
