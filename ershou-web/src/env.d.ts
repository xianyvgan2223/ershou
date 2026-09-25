/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 后端接口基础路径，开发/生产均为 /api/v1 */
  readonly VITE_API_BASE: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
