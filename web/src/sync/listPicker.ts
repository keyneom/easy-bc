/**
 * App-local Google Picker for selecting the shared dataset files.
 *
 * sync-kit's picker uses the default grid view, where touch devices force
 * one-file-at-a-time selection (the "pick one, reopen, pick the next" pain).
 * The LIST view mode renders a checkbox per row once MULTISELECT_ENABLED is
 * on, so every shared file can be selected in a single Picker visit on both
 * desktop and mobile.
 */

const PICKER_SCRIPT = "https://apis.google.com/js/api.js";
const DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder";

type PickerDocument = {
  id?: string;
  name?: string;
  mimeType?: string;
  url?: string;
};

type PickerResponse = {
  action?: string;
  docs?: PickerDocument[];
};

type Picker = {
  setVisible(visible: boolean): void;
  dispose?(): void;
};

type DocsView = {
  setIncludeFolders(value: boolean): DocsView;
  setMimeTypes(value: string): DocsView;
  setSelectFolderEnabled(value: boolean): DocsView;
  setOwnedByMe(value: boolean): DocsView;
  setMode(mode: string): DocsView;
};

type PickerBuilder = {
  addView(view: DocsView): PickerBuilder;
  enableFeature(feature: string): PickerBuilder;
  setAppId(appId: string): PickerBuilder;
  setCallback(callback: (response: PickerResponse) => void): PickerBuilder;
  setDeveloperKey(key: string): PickerBuilder;
  setOAuthToken(token: string): PickerBuilder;
  setOrigin(origin: string): PickerBuilder;
  setTitle(title: string): PickerBuilder;
  build(): Picker;
};

type GooglePicker = {
  Action: { PICKED: string; CANCEL: string };
  DocsView: new (viewId: string) => DocsView;
  DocsViewMode: { LIST: string; GRID: string };
  PickerBuilder: new () => PickerBuilder;
  ViewId: { FOLDERS: string; DOCS: string };
  Feature: { MULTISELECT_ENABLED: string };
};

type GoogleApiWindow = Window & {
  gapi?: {
    load(name: string, callback: { callback(): void; onerror(): void }): void;
  };
  google?: { picker?: GooglePicker };
};

export type PickedDriveFile = { fileId: string; name?: string; url?: string };

let scriptPromise: Promise<void> | null = null;
let pickerPromise: Promise<void> | null = null;

function browserWindow(): GoogleApiWindow {
  if (typeof window === "undefined") {
    throw new Error("Google Picker requires a browser window.");
  }
  return window as GoogleApiWindow;
}

function loadScript(): Promise<void> {
  if (browserWindow().gapi) return Promise.resolve();
  scriptPromise ??= new Promise((resolve, reject) => {
    const script = document.createElement("script");
    script.src = PICKER_SCRIPT;
    script.async = true;
    script.defer = true;
    script.onload = () => resolve();
    script.onerror = () => {
      scriptPromise = null;
      reject(new Error("The Google API loader could not be loaded."));
    };
    document.head.append(script);
  });
  return scriptPromise;
}

async function loadPicker(): Promise<GooglePicker> {
  const existing = browserWindow().google?.picker;
  if (existing) return existing;
  await loadScript();
  pickerPromise ??= new Promise((resolve, reject) => {
    const gapi = browserWindow().gapi;
    if (!gapi) {
      reject(new Error("The Google API loader is unavailable."));
      return;
    }
    gapi.load("picker", {
      callback: () => resolve(),
      onerror: () => {
        pickerPromise = null;
        reject(new Error("The Google Picker API could not be loaded."));
      },
    });
  });
  await pickerPromise;
  const picker = browserWindow().google?.picker;
  if (!picker) throw new Error("Google Picker loaded without its file API.");
  return picker;
}

/**
 * Multi-select file picker in LIST mode. Shows files shared by other accounts
 * (setOwnedByMe(false)) with folders included for navigation into the shared
 * EasyBC folder; each selected file gets a `drive.file` grant for this app.
 */
export async function pickFilesWithCheckboxes(input: {
  accessToken: string;
  developerKey: string;
  cloudProjectNumber: string;
  title: string;
  origin?: string;
}): Promise<PickedDriveFile[]> {
  const pickerApi = await loadPicker();
  return new Promise((resolve, reject) => {
    let picker: Picker | undefined;
    const finish = (value: PickedDriveFile[]): void => {
      picker?.dispose?.();
      resolve(value);
    };
    const fail = (error: Error): void => {
      picker?.dispose?.();
      reject(error);
    };
    const view = new pickerApi.DocsView(pickerApi.ViewId.DOCS)
      .setIncludeFolders(true)
      .setSelectFolderEnabled(false)
      .setOwnedByMe(false)
      .setMode(pickerApi.DocsViewMode.LIST);
    try {
      picker = new pickerApi.PickerBuilder()
        .addView(view)
        .enableFeature(pickerApi.Feature.MULTISELECT_ENABLED)
        .setAppId(input.cloudProjectNumber)
        .setDeveloperKey(input.developerKey)
        .setOAuthToken(input.accessToken)
        .setOrigin(input.origin ?? window.location.origin)
        .setTitle(input.title)
        .setCallback((response) => {
          if (response.action === pickerApi.Action.CANCEL) {
            finish([]);
            return;
          }
          if (response.action !== pickerApi.Action.PICKED) return;
          const docs = (response.docs ?? []).filter(
            (doc): doc is { id: string; name?: string; url?: string } =>
              Boolean(doc.id) && doc.mimeType !== DRIVE_FOLDER_MIME_TYPE,
          );
          if (docs.length === 0) {
            fail(new Error("Google Picker did not return a Drive file."));
            return;
          }
          finish(
            docs.map((doc) => ({
              fileId: doc.id,
              ...(doc.name ? { name: doc.name } : {}),
              ...(doc.url ? { url: doc.url } : {}),
            })),
          );
        })
        .build();
      picker.setVisible(true);
    } catch (error) {
      fail(
        new Error("Google Picker could not be opened.", { cause: error }),
      );
    }
  });
}
