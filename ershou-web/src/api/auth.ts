import { http } from '@/utils/request'
import type { Category } from './types'

/** 发送短信验证码 */
export interface SmsCodeParams {
  phone: string
}

/** 登录入参：未选择学校时前端默认 schoolId=1, campusId=1 */
export interface LoginParams {
  phone: string
  code: string
  schoolId: number
  campusId: number
}

/** 登录返回的用户概要 */
export interface LoginUser {
  id: number
  nickname: string
  avatar: string
  campusId: number
  campusName: string
  creditScore: number
  identityFlag: number
  newUser: boolean
}

export interface LoginResult {
  token: string
  user: LoginUser
}

/** GET /users/me 返回的完整资料 */
export interface UserProfile {
  id: number
  nickname: string
  avatar: string
  /** 已脱敏手机号 */
  phone: string
  schoolId: number | null
  schoolName: string
  campusId: number | null
  campusName: string
  creditScore: number
  creditLevel: string
  shopName: string
  /** 小铺简介（选填，仅商家用） */
  shopIntro: string | null
  identityFlag: number
  publishLimit: number
  chatLimit: number
  /** 加入时间 */
  createdAt: string | null
  /** role_flags 位标记（1 普通 / 2 商家 / 4 代卖员 / 8 运营） */
  roleFlags?: number
  /** 是否运营人员 */
  isAdmin?: boolean
}

export function sendSmsCode(phone: string): Promise<null> {
  return http.post<null>('/auth/sms-code', { phone } satisfies SmsCodeParams)
}

export function login(params: LoginParams): Promise<LoginResult> {
  return http.post<LoginResult>('/auth/login', params)
}

export function fetchCurrentUser(): Promise<UserProfile> {
  return http.get<UserProfile>('/users/me')
}

export function fetchCategories(): Promise<Category[]> {
  return http.get<Category[]>('/categories')
}
