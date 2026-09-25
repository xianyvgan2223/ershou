import { http } from '@/utils/request'

export interface UploadResult {
  url: string
}

/** 上传业务类型 query 参数 */
export type UploadBiz = 'item' | 'avatar' | 'chat'

const API_BASE = import.meta.env.VITE_API_BASE || '/api/v1'

/** el-upload 直接使用的上传地址 */
export function buildUploadUrl(biz: UploadBiz = 'item'): string {
  return `${API_BASE}/files/upload?biz=${biz}`
}

/** el-upload 需要的鉴权头 */
export function buildUploadHeaders(token: string): Record<string, string> {
  return token ? { Authorization: `Bearer ${token}` } : {}
}

/** 手动上传（multipart/form-data，字段名 file） */
export function uploadFile(file: File, biz: UploadBiz = 'item'): Promise<UploadResult> {
  const form = new FormData()
  form.append('file', file)
  return http.post<UploadResult>(buildUploadUrl(biz), form)
}
