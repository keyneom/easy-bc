/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_GOOGLE_WEB_CLIENT_ID?: string;
  readonly VITE_GOOGLE_API_KEY?: string;
  readonly VITE_GOOGLE_CLOUD_PROJECT_NUMBER?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
