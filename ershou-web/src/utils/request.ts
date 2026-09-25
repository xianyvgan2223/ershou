import axios from 'axios'
import type {
  AxiosError,
  AxiosInstance,
  AxiosRequestConfig,
  AxiosResponse,
  InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'

/** 后端统一响应体：{ code, message, data, traceId } */
export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  traceId?: string
}

/** 业务异常（code !== 0 时抛出） */
export class ApiError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

/** 登录态失效的业务码 */
export const CODE_UNAUTHORIZED = 10002

const TOKEN_KEY = 'ershou_token'

export function getToken(): string {
  return localStorage.getItem(TOKEN_KEY) ?? ''
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY)
}

let redirectingToLogin = false

/** 清理登录态并跳转登录页（避免同一个页面里重复提示/重复跳转） */
function redirectToLogin(message: string): void {
  clearToken()
  if (redirectingToLogin) return
  redirectingToLogin = true
  ElMessage.error(message)
  const current = window.location.pathname + window.location.search
  const redirect = current.startsWith('/login') ? '/' : current
  window.setTimeout(() => {
    redirectingToLogin = false
    window.location.href = `/login?redirect=${encodeURIComponent(redirect)}`
  }, 600)
}

const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 15000,
})

// 请求拦截：注入 Authorization: Bearer <token>
service.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const token = getToken()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// 响应拦截：解包 { code, message, data, traceId }
service.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    const result = response.data
    // 非标准响应（例如后端直接返回文件流）原样返回
    if (!result || typeof result !== 'object' || typeof result.code !== 'number') {
      return result as unknown as AxiosResponse
    }
    if (result.code === 0) {
      return result.data as unknown as AxiosResponse
    }
    if (result.code === CODE_UNAUTHORIZED) {
      redirectToLogin(result.message || '登录状态已过期，请重新登录')
      return Promise.reject(new ApiError(result.code, result.message, result.traceId))
    }
    ElMessage.error(result.message || '请求失败，请稍后重试')
    return Promise.reject(new ApiError(result.code, result.message, result.traceId))
  },
  (error: AxiosError<ApiResult>) => {
    const status = error.response?.status
    const backendMessage = error.response?.data?.message
    let message = backendMessage || ''
    if (!message) {
      if (status === 401 || status === 403) message = '登录状态已过期，请重新登录'
      else if (error.code === 'ECONNABORTED') message = '请求超时，请稍后重试'
      else if (status === 404) message = '接口不存在，请检查后端服务'
      else if (status && status >= 500) message = '服务器开小差了，请稍后重试'
      else message = '网络异常，请确认后端服务已启动（127.0.0.1:8080）'
    }
    if (status === 401 || status === 403) {
      redirectToLogin(message)
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(error instanceof Error ? error : new Error(message))
  },
)

function request<T>(config: AxiosRequestConfig): Promise<T> {
  return service.request<unknown, T>(config)
}

/** 轻量 http 封装：返回值已由拦截器解包为业务 data */
export const http = {
  get<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ url, method: 'GET', params, ...config })
  },
  post<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ url, method: 'POST', data, ...config })
  },
  patch<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ url, method: 'PATCH', data, ...config })
  },
  delete<T>(url: string, params?: object, config?: AxiosRequestConfig): Promise<T> {
    return request<T>({ url, method: 'DELETE', params, ...config })
  },
}

export default http
