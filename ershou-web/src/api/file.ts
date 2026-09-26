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

/**
 * 手动上传（multipart/form-data，字段名 file）
 *
 * 注意：http 走 axios，而 axios 已经配了 baseURL=/api/v1，
 * 所以这里必须用**相对路径 + params**——不能复用 buildUploadUrl()，
 * 那个返回的是给 el-upload 用的绝对路径（/api/v1/files/upload），
 * 再交给 axios 会被拼成 /api/v1/api/v1/files/upload 直接 404。
 * （biz 放 params 而不是手拼 query，避免将来参数多了忘记编码。）
 */
export function uploadFile(file: File, biz: UploadBiz = 'item'): Promise<UploadResult> {
  const form = new FormData()
  form.append('file', file)
  return http.post<UploadResult>('/files/upload', form, { params: { biz } })
}
