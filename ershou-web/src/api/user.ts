import { http } from '@/utils/request'
import type { UserProfile } from './auth'

/**
 * 编辑资料入参（PATCH /users/me）。
 *
 * 字段全部可选：只提交要改的字段，没提交的（undefined）后端不动；
 * 头像传空串表示恢复默认头像。学校 / 校区要一起传，否则后端会校验"校区属于该校"。
 */
export interface UpdateProfileParams {
  nickname?: string
  avatar?: string
  schoolId?: number
  campusId?: number
  shopName?: string
  shopIntro?: string
}

/** 用户主页（后端 PublicUserVO，公开接口，不含任何联系方式） */
export interface PublicUserVO {
  id: number
  nickname: string | null
  avatar: string | null
  creditScore: number | null
  creditLevel: number | null
  /** 1=商家 */
  identityFlag: number | null
  shopName: string | null
  schoolName: string | null
  campusName: string | null
  createdAt: string | null
  /** 3=封禁（主页仍可访问，前端提示） */
  status: number | null
  /** 在售商品数 */
  onSaleCount: number
  /** 当前查看者是否已拉黑 TA */
  blocked: boolean
  /** TA 是否拉黑了当前查看者 */
  blockedByPeer: boolean
  /** 是否是我自己 */
  self: boolean
}

export interface UserBlockRow {
  id: number
  userId: number
  blockedUserId: number
  createdAt: string
}

/** 编辑我的资料：昵称 / 头像 / 学校校区 / 小铺信息，返回更新后的完整资料 */
export function updateMyProfile(params: UpdateProfileParams): Promise<UserProfile> {
  return http.patch<UserProfile>('/users/me', params)
}

/** 用户主页（未登录也能看） */
export function fetchUserProfile(userId: number | string): Promise<PublicUserVO> {
  return http.get<PublicUserVO>(`/users/${userId}/profile`)
}

/** 拉黑：拉黑后双方不可私聊 */
export function blockUser(userId: number | string): Promise<null> {
  return http.post<null>(`/users/${userId}/block`)
}

export function unblockUser(userId: number | string): Promise<null> {
  return http.delete<null>(`/users/${userId}/block`)
}

/** 我拉黑过的人 */
export function fetchBlockedUsers(): Promise<UserBlockRow[]> {
  return http.get<UserBlockRow[]>('/users/blocks')
}
