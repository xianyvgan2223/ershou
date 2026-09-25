import { http } from '@/utils/request'
import type { PageQuery, PageResult } from './types'

/** GET /admin/dashboard */
export interface AdminDashboard {
  pendingItems: number
  pendingPoints: number
  onSaleItems: number
  rejectedItems: number
  totalItems: number
  totalUsers: number
  bannedUsers: number
  todayOperations: number
}

/** 运营后台的商品视图 */
export interface AdminItem {
  id: number
  title: string
  description?: string | null
  price: number
  originalPrice?: number | null
  conditionLevel?: number
  conditionText?: string
  coverUrl?: string | null
  images: string[]
  categoryId?: number
  categoryName?: string
  campusId?: number
  campusName?: string
  preferPointId?: number | null
  preferPointName?: string | null
  sellerId: number
  sellerNickname?: string
  sellerPhone?: string | null
  sellerCreditScore?: number
  status: number
  statusText?: string
  auditResult?: number | null
  auditReason?: string | null
  rejectCode?: string | null
  viewCount?: number
  favCount?: number
  stock?: number
  publishedAt?: string | null
  expireAt?: string | null
  createdAt?: string | null
}

/** 运营后台的面交点视图 */
export interface AdminPoint {
  id: number
  campusId?: number
  campusName?: string
  name: string
  source?: number
  pointType?: number
  coordX?: number
  coordY?: number
  safetyScore?: number
  safeTags?: string[]
  guideText?: string | null
  status?: number
  auditStatus?: number
  creatorId?: number | null
  creatorNickname?: string
  createdAt?: string | null
}

/** 运营后台的用户视图 */
export interface AdminUser {
  id: number
  nickname: string
  avatar?: string | null
  phone?: string | null
  schoolId?: number | null
  campusId?: number | null
  shopName?: string | null
  roleFlags?: number
  identityFlag?: number
  creditScore?: number
  creditLevel?: number
  creditLevelText?: string
  riskScore?: number
  status?: number
  statusText?: string
  banUntil?: string | null
  publishLimit?: number
  chatLimit?: number
  deviceId?: string | null
  deviceAccountCount?: number
  lastLoginAt?: string | null
  createdAt?: string | null
}

/** 运营后台的面交单视图 */
export interface AdminOrder {
  id: number
  orderNo: string
  batchNo?: string | null
  itemId?: number
  itemTitle?: string
  itemCover?: string | null
  quantity?: number
  dealPrice?: number
  buyerId?: number
  buyerNickname?: string
  sellerId?: number
  sellerNickname?: string
  pointId?: number | null
  pointName?: string | null
  timeSlotStart?: string | null
  timeSlotEnd?: string | null
  status?: number
  statusText?: string
  verifiedAt?: string | null
  buyerConfirmAt?: string | null
  sellerConfirmAt?: string | null
  cancelReason?: string | null
  createdAt?: string | null
}

/** 操作审计日志 */
export interface AuditLogItem {
  id: number
  operatorId?: number
  operatorNickname?: string
  module?: string
  action?: string
  targetType?: string | null
  targetId?: number | null
  beforeValue?: unknown
  afterValue?: unknown
  ip?: string | null
  createdAt?: string | null
}

export interface RejectReason {
  code: string
  label: string
  scope: string
}

export interface BatchAuditResult {
  success: number
  failed: number
  failures: { id: number; reason: string }[]
}

export type AuditScope = 'item' | 'point'

export function fetchAdminDashboard(): Promise<AdminDashboard> {
  return http.get<AdminDashboard>('/admin/dashboard')
}

/** 商品审核队列 */
export function fetchAuditItems(
  params: PageQuery & { status?: number; keyword?: string },
): Promise<PageResult<AdminItem>> {
  return http.get<PageResult<AdminItem>>('/admin/audit/items', params)
}

export function approveAuditItem(id: number): Promise<null> {
  return http.post<null>(`/admin/audit/items/${id}/approve`)
}

export function rejectAuditItem(
  id: number,
  data: { reasonCode?: string; reason?: string },
): Promise<null> {
  return http.post<null>(`/admin/audit/items/${id}/reject`, data)
}

export function batchAuditItems(data: {
  ids: number[]
  action: 'approve' | 'reject'
  reasonCode?: string
  reason?: string
}): Promise<BatchAuditResult> {
  return http.post<BatchAuditResult>('/admin/audit/items/batch', data)
}

export function fetchRejectReasons(scope: AuditScope): Promise<RejectReason[]> {
  return http.get<RejectReason[]>('/admin/audit/reject-reasons', { scope })
}

/** 点位审核队列 */
export function fetchAuditPoints(
  params: PageQuery & { auditStatus?: number; campusId?: number; keyword?: string },
): Promise<PageResult<AdminPoint>> {
  return http.get<PageResult<AdminPoint>>('/admin/audit/points', params)
}

export function approveAuditPoint(
  id: number,
  data: { safetyScore?: number; guideText?: string; safeTags?: string[] },
): Promise<null> {
  return http.post<null>(`/admin/audit/points/${id}/approve`, data)
}

export function rejectAuditPoint(
  id: number,
  data: { reasonCode?: string; reason?: string },
): Promise<null> {
  return http.post<null>(`/admin/audit/points/${id}/reject`, data)
}

/** 商品管理（全部状态） */
export function fetchAdminItems(
  params: PageQuery & { status?: number; keyword?: string; sellerId?: number },
): Promise<PageResult<AdminItem>> {
  return http.get<PageResult<AdminItem>>('/admin/items', params)
}

export function forceOfflineItem(id: number, reason: string): Promise<null> {
  return http.post<null>(`/admin/items/${id}/offline`, { reason })
}

export function fetchAdminOrders(
  params: PageQuery & { status?: number; buyerId?: number; sellerId?: number },
): Promise<PageResult<AdminOrder>> {
  return http.get<PageResult<AdminOrder>>('/admin/orders', params)
}

export function fetchAdminUsers(
  params: PageQuery & { keyword?: string; status?: number },
): Promise<PageResult<AdminUser>> {
  return http.get<PageResult<AdminUser>>('/admin/users', params)
}

export function updateUserStatus(
  id: number,
  data: { status: number; banDays?: number; reason?: string },
): Promise<AdminUser> {
  return http.post<AdminUser>(`/admin/users/${id}/status`, data)
}

export function adjustUserCredit(
  id: number,
  data: { changeValue: number; reason: string },
): Promise<AdminUser> {
  return http.post<AdminUser>(`/admin/users/${id}/credit`, data)
}

export function fetchAuditLogs(
  params: PageQuery & { module?: string; operatorId?: number },
): Promise<PageResult<AuditLogItem>> {
  return http.get<PageResult<AuditLogItem>>('/admin/audit-logs', params)
}
