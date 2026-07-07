/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
  readonly VITE_SECRET_KEY: string;
  readonly VITE_APP_TITLE: string;
  readonly VITE_PRIMEVUE_LICENSE_KEY?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}