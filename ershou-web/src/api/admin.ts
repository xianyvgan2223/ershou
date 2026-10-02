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
  /**
   * 面交单与点位补充统计。
   * 标成可选是为了兼容没重启的旧后端：那时候这几个字段还没下发，
   * 页面按 0 兜底而不是显示 undefined。
   */
  totalOrders?: number
  activeOrders?: number
  doneOrders?: number
  riskOrders?: number
  todayOrders?: number
  disabledPoints?: number
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
  /** 第三方地图经纬度；用户提交的点位通常只有经纬度 */
  lng?: number | null
  lat?: number | null
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

/** 点位列表 / 审核队列 */
export function fetchAuditPoints(
  params: PageQuery & {
    /** 0 待审 / 1 已通过 / 2 已驳回；负数 = 不限审核状态 */
    auditStatus?: number
    campusId?: number
    keyword?: string
    /** 地图启用状态：1 启用 / 0 已下架 */
    status?: number
  },
): Promise<PageResult<AdminPoint>> {
  return http.get<PageResult<AdminPoint>>('/admin/audit/points', params)
}

/**
 * 启用 / 停用（下架）面交点。
 * 停用会让点位立刻从地图上消失（后端按 `trade_point.status = 1` 过滤），
 * 用于处置事后发现不合适的用户自定义点位。
 */
export function updatePointStatus(
  id: number,
  data: { status: number; reason?: string },
): Promise<null> {
  return http.post<null>(`/admin/points/${id}/status`, data)
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
  params: PageQuery & { status?: number; buyerId?: number; sellerId?: number; keyword?: string },
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
  params: PageQuery & {
    module?: string
    action?: string
    operatorId?: number
    /** 'yyyy-MM-dd HH:mm:ss'；后端也兼容 ISO 与纯日期 */
    startTime?: string
    endTime?: string
  },
): Promise<PageResult<AuditLogItem>> {
  return http.get<PageResult<AuditLogItem>>('/admin/audit-logs', params)
}

/* ==================================================================
 *  上架审核（运营后台「上架审核」页）
 * ================================================================== */

/** 上架审核记录：一条 = 一个商品在本轮上架中的"待确认"状态 */
export interface ItemReview {
  id: number
  itemId: number
  /** 商品当前标题（实时） */
  title: string
  /** 入队时的标题快照；与 title 不同说明卖家在待审期间又改了名 */
  titleSnapshot?: string | null
  description?: string | null
  coverUrl?: string | null
  images: string[]

  sellerId?: number | null
  sellerNickname?: string | null
  sellerPhone?: string | null
  sellerCreditScore?: number | null

  categoryId?: number | null
  categoryName?: string | null
  campusId?: number | null
  campusName?: string | null
  preferPointId?: number | null
  preferPointName?: string | null
  price?: number | null

  itemStatus?: number | null
  itemStatusText?: string | null
  /** 商品是否还能被处置（false = 已被卖掉/下架，按钮置灰） */
  actionable?: boolean

  /** 1=新上架 2=改名/改描述 3=重新上架 4=违规后二次上架 5=存量导入 */
  reviewType?: number | null
  reviewTypeText?: string | null
  revision?: number | null

  /** 0=待审核 1=已审核 */
  status?: number | null
  statusText?: string | null
  /** 1=已审核 2=已下架 */
  reviewAction?: number | null
  reviewActionText?: string | null

  queuedAt?: string | null
  firstQueuedAt?: string | null
  contentChangedAt?: string | null
  reviewedAt?: string | null
  note?: string | null

  reviewerId?: number | null
  reviewerNickname?: string | null
  claimedAt?: string | null

  contentChanged?: boolean
  violationBefore?: boolean
  reviewRound?: number
  rejectCount?: number

  publishedAt?: string | null
  expireAt?: string | null
}

/** 上架审核顶部统计 */
export interface ItemReviewStats {
  pending: number
  reviewedToday: number
  queuedToday: number
  onSaleTotal: number
  stalePending: number
  onlineReviewers: number
  serverTime?: string | null
}

export type ReviewScope = 'pending' | 'done' | 'all'

/** 上架审核列表（scope=pending 时含最近 24h 内已处置的，便于回看） */
export function fetchReviewFeed(
  params: PageQuery & { scope?: ReviewScope; sellerId?: number; keyword?: string },
): Promise<PageResult<ItemReview>> {
  return http.get<PageResult<ItemReview>>('/admin/review/feed', params)
}

export function fetchReviewStats(): Promise<ItemReviewStats> {
  return http.get<ItemReviewStats>('/admin/review/stats')
}

export function fetchReviewDetail(itemId: number): Promise<ItemReview> {
  return http.get<ItemReview>(`/admin/review/items/${itemId}`)
}

/** 已审核：确认看过没问题 → 该条从待审列表消失（归档留痕） */
export function markItemReviewed(itemId: number, note?: string): Promise<ItemReviewStats> {
  return http.post<ItemReviewStats>(`/admin/review/items/${itemId}/reviewed`, { reason: note })
}

/** 下架：内容不合适，撤下来（卖家改好可以重新上架） */
export function offlineReviewItem(itemId: number, reason?: string): Promise<ItemReviewStats> {
  return http.post<ItemReviewStats>(`/admin/review/items/${itemId}/offline`, { reason })
}

/** 批量处置：action=review 已审核 / action=offline 下架 */
export function batchReview(
  action: 'review' | 'offline',
  data: { ids: number[]; reason?: string },
): Promise<BatchAuditResult> {
  return http.post<BatchAuditResult>(`/admin/review/batch/${action}`, data)
}

/** 补登记：把"在售却没有审核记录"的商品补进队列（兜底用） */
export function backfillReviews(limit = 500): Promise<number> {
  return http.post<number>(`/admin/review/backfill?limit=${limit}`)
}
