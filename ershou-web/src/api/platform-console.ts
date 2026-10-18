import { http } from '@/utils/request'
import type { PageQuery, PageResult } from './types'

/**
 * 平台运营台（独立操作台）接口与类型。
 *
 * 契约：`智能体团队/第3轮-平台运营台-设计.md` 第 3 节（后端接口）与 4.3 节（本文件清单）。
 *
 * 约定（重要）：
 * - **本文件是运营台管理接口的唯一出口**；平台商城/代卖点 CRUD 的既有函数仍在 `@/api/platform`
 *   （`publishPlatformItem` / `getAdminPoints` / `saveAdminPoint` / `updateAdminPoint` /
 *   `deleteAdminPoint` / `toggleAdminPointStatus` / `fetchPlatformItems`），这里不重复实现、不复制类型。
 * - 后端统一前缀 `/api/v1/platform/admin`（`baseURL` 已是 `/api/v1`），每个接口都要求运营身份。
 * - 函数名已定稿：**改名必须先通知 lead 与 map-member**（5 个页面按本文件调用）。
 * - 返回值已被 `utils/request.ts` 拦截器解包成业务 `data`；写操作后后端负责清缓存/同步 ES。
 */

/* ------------------------------------------------------------------ *
 * 3.1 概览统计
 * ------------------------------------------------------------------ */

/**
 * 运营台概览：`GET /platform/admin/overview?campusId=2`。
 *
 * 口径（设计 3.1）：
 * - `orderToday` / `gmvToday` 只算平台账号（`seller_id=1000`）且 `created_at >= 今天 0 点`；
 * - `gmv = deal_price * quantity`；
 * - `pendingPickup` = 待自提（status=2），`waitingConfirm` = 待确认（status=1）；
 * - `lowStockItems` = 在售平台商品里 `stock <= 2` 的数量；
 * - `expiringFlashHours` = 进行中场次距结束的小时数，无场次为 `null`。
 */
export interface ConsoleOverviewVO {
  /** 在售平台商品数 */
  platformItemOnSale: number
  /** 平台商品总数（含已下架/售出等全部状态） */
  platformItemTotal: number
  /** 代卖点数量 */
  pointCount: number
  /** 券模板数量 */
  couponCount: number
  /** 券总库存 */
  couponTotalStock: number
  /** 券已领取总量 */
  couponClaimed: number
  /** 进行中/最近场次 id；无场次为 null */
  flashSceneId: number | null
  flashSceneTitle: string | null
  /** 场次状态：1 预告 / 2 进行中 / 3 结束；无场次为 null */
  flashSceneStatus: number | null
  /** 秒杀剩余库存（进行中场次汇总） */
  flashStockLeft: number
  /** 秒杀总库存 */
  flashStockTotal: number
  /** 秒杀已售 */
  flashSold: number
  /** 今日平台订单数 */
  orderToday: number
  /** 平台订单总数 */
  orderTotal: number
  /** 今日 GMV */
  gmvToday: number
  /** 累计 GMV */
  gmvTotal: number
  /** 待自提（status=2） */
  pendingPickup: number
  /** 待确认（status=1） */
  waitingConfirm: number
  /** 在售平台商品中库存 <= 2 的数量 */
  lowStockItems: number
  /** 进行中场次距结束小时数；无场次为 null */
  expiringFlashHours: number | null
}

/* ------------------------------------------------------------------ *
 * 3.2 平台商品管理
 * ------------------------------------------------------------------ */

/** 后台平台商品（含全部状态）：`GET /platform/admin/items` 的 `records[]` */
export interface ConsoleAdminItemVO {
  id: number
  title: string
  price: number
  originalPrice?: number | null
  coverUrl?: string | null
  stock?: number | null
  /** 1 审核中 / 2 在售 / 4 已售出 / 5 已下架 / 7 已过期 */
  status: number
  statusText?: string | null
  pointId?: number | null
  pointName?: string | null
  /** 固定 2（平台商品） */
  sellerType?: 1 | 2
  viewCount?: number | null
  publishedAt?: string | null
  expireAt?: string | null
}

/** 平台商品分页查询（`status` 不传 = 全部状态） */
export interface AdminItemQuery extends PageQuery {
  status?: number
  /** 匹配 title */
  keyword?: string
  campusId?: number
  /** 按代卖点筛选 */
  pointId?: number
}

/** 编辑平台商品入参（只允许改标题/价格/库存/代卖点） */
export interface ConsoleItemUpdateParams {
  title?: string
  price?: number
  stock?: number
  platformPointId?: number
}

/* ------------------------------------------------------------------ *
 * 3.3 优惠券管理
 * ------------------------------------------------------------------ */

/** 后台券视图：`GET /platform/admin/coupons` */
export interface ConsoleAdminCouponVO {
  id: number
  name: string
  /** 1 满减 / 2 折扣 */
  type: number
  /** 满减门槛（type=1） */
  thresholdAmount?: number | null
  /** 减免金额（type=1） */
  discountAmount?: number | null
  /** 折扣率（type=2），后端可能下发 0.9 或 90 */
  discountRate?: number | null
  totalStock: number
  perUserLimit: number
  startAt: string
  endAt: string
  status: number
  statusText?: string | null
  campusId?: number | null
  /** 已领取数量（DB 真值） */
  claimedCount: number
  /** 剩余库存（优先 Redis，未预热回退 totalStock-claimedCount） */
  leftStock: number
}

/** 新建 / 编辑券入参（`POST/PUT /platform/admin/coupons`） */
export interface ConsoleCouponSaveParams {
  name: string
  /** 1 满减 / 2 折扣 */
  type: number
  thresholdAmount?: number | null
  discountAmount?: number | null
  discountRate?: number | null
  totalStock: number
  perUserLimit: number
  startAt: string
  endAt: string
  campusId?: number
  status?: number
}

/* ------------------------------------------------------------------ *
 * 3.4 秒杀场次管理
 * ------------------------------------------------------------------ */

/** 场次里的秒杀商品 */
export interface ConsoleAdminFlashItemVO {
  itemId: number
  title: string
  coverUrl?: string | null
  /** 原价（划线价） */
  originPrice: number
  seckillPrice: number
  /** 秒杀总库存 */
  seckillStock: number
  perUserLimit: number
  soldCount: number
  /** 实时剩余（Redis） */
  leftStock: number
  sort?: number
}

/** 后台秒杀场次：`GET /platform/admin/flash-sales` */
export interface ConsoleAdminFlashSaleVO {
  id: number
  title: string
  /** 1 预告 / 2 进行中 / 3 结束 */
  status: number
  statusText?: string | null
  startAt: string
  endAt: string
  sort?: number
  campusId?: number | null
  items: ConsoleAdminFlashItemVO[]
}

/** 新建 / 编辑场次入参 */
export interface ConsoleFlashSaleSaveParams {
  title: string
  startAt: string
  endAt: string
  /** 1 预告 / 2 进行中 / 3 结束 */
  status?: number
  campusId?: number
  sort?: number
}

/** 场次添加商品入参 */
export interface ConsoleFlashItemSaveParams {
  itemId: number
  seckillPrice: number
  seckillStock: number
  perUserLimit: number
  sort?: number
}

/* ------------------------------------------------------------------ *
 * 3.5 平台订单
 * ------------------------------------------------------------------ */

/** 平台订单（`meetup_order.seller_id = 平台账号`）：`GET /platform/admin/orders` */
export interface ConsoleAdminOrderVO {
  id: number
  orderNo: string
  itemId: number
  itemTitle?: string | null
  itemCover?: string | null
  buyerId?: number | null
  buyerNickname?: string | null
  quantity?: number | null
  dealPrice: number
  /** 1 待卖家确认 / 2 待面交(自提) / 3 已完成 / 5 已失效 / 6 爽约 / 7 纠纷 */
  status: number
  statusText?: string | null
  pointId?: number | null
  pointName?: string | null
  createdAt: string
  verifiedAt?: string | null
}

/** 平台订单分页查询 */
export interface AdminOrderQuery extends PageQuery {
  status?: number
  /** 匹配 order_no / item_title */
  keyword?: string
}

/* ------------------------------------------------------------------ *
 * 3.6 缓存运维
 * ------------------------------------------------------------------ */

/** 单个 `ershou:platform:*` key 的快照（只读） */
export interface ConsoleCacheKeyVO {
  key: string
  /** Redis 类型：string / hash / ...（后端给的小写类型名） */
  type: string
  /** 剩余 TTL（秒，-1=永不过期，-2=不存在） */
  ttl: number
  /** String 给库存值；Hash 给 field 数量（size） */
  value: string | number | null
}

/** 缓存统计：`GET /platform/admin/cache/stats` */
export interface ConsoleCacheStatsVO {
  /** 预热分布式锁当前是否被持有 */
  warmupLockHeld: boolean
  /** 扫描到的 key 总数 */
  totalKeys: number
  keys: ConsoleCacheKeyVO[]
}

/* ------------------------------------------------------------------ *
 * 3.1 概览统计
 * ------------------------------------------------------------------ */

/** 运营台概览（`campusId` 不传走后端默认校区） */
export function fetchConsoleOverview(campusId?: number): Promise<ConsoleOverviewVO> {
  return http.get<ConsoleOverviewVO>('/platform/admin/overview', campusId ? { campusId } : undefined)
}

/* ------------------------------------------------------------------ *
 * 3.2 平台商品
 * ------------------------------------------------------------------ */

/** 平台商品分页（含全部状态） */
export function fetchConsoleItems(query: AdminItemQuery): Promise<PageResult<ConsoleAdminItemVO>> {
  return http.get<PageResult<ConsoleAdminItemVO>>('/platform/admin/items', query)
}

/** 编辑平台商品（标题/价格/库存/代卖点） */
export function updateConsoleItem(
  id: number,
  data: ConsoleItemUpdateParams,
): Promise<ConsoleAdminItemVO> {
  return http.put<ConsoleAdminItemVO>(`/platform/admin/items/${id}`, data)
}

/** 下架平台商品（status=5） */
export function offlineConsoleItem(id: number): Promise<null> {
  return http.post<null>(`/platform/admin/items/${id}/offline`)
}

/** 重新上架平台商品（status=2、有效期 +30 天） */
export function onlineConsoleItem(id: number): Promise<null> {
  return http.post<null>(`/platform/admin/items/${id}/online`)
}

/* ------------------------------------------------------------------ *
 * 3.3 优惠券
 * ------------------------------------------------------------------ */

/** 券列表（含 claimedCount / leftStock） */
export function fetchConsoleCoupons(campusId?: number): Promise<ConsoleAdminCouponVO[]> {
  return http.get<ConsoleAdminCouponVO[]>('/platform/admin/coupons', campusId ? { campusId } : undefined)
}

/** 新建券 */
export function saveConsoleCoupon(data: ConsoleCouponSaveParams): Promise<ConsoleAdminCouponVO> {
  return http.post<ConsoleAdminCouponVO>('/platform/admin/coupons', data)
}

/** 编辑券（已发放数 > 新 totalStock 时后端拒绝） */
export function updateConsoleCoupon(
  id: number,
  data: ConsoleCouponSaveParams,
): Promise<ConsoleAdminCouponVO> {
  return http.put<ConsoleAdminCouponVO>(`/platform/admin/coupons/${id}`, data)
}

/** 券启停（status=1 启用 / 0 停用） */
export function toggleConsoleCoupon(id: number, status: 0 | 1): Promise<null> {
  return http.post<null>(`/platform/admin/coupons/${id}/status`, undefined, { params: { status } })
}

/* ------------------------------------------------------------------ *
 * 3.4 秒杀场次
 * ------------------------------------------------------------------ */

/** 场次列表（含商品明细与实时剩余） */
export function fetchConsoleFlashSales(campusId?: number): Promise<ConsoleAdminFlashSaleVO[]> {
  return http.get<ConsoleAdminFlashSaleVO[]>(
    '/platform/admin/flash-sales',
    campusId ? { campusId } : undefined,
  )
}

/** 新建场次 */
export function saveConsoleFlashSale(
  data: ConsoleFlashSaleSaveParams,
): Promise<ConsoleAdminFlashSaleVO> {
  return http.post<ConsoleAdminFlashSaleVO>('/platform/admin/flash-sales', data)
}

/** 编辑场次（时间 / 状态 / 排序） */
export function updateConsoleFlashSale(
  id: number,
  data: ConsoleFlashSaleSaveParams,
): Promise<ConsoleAdminFlashSaleVO> {
  return http.put<ConsoleAdminFlashSaleVO>(`/platform/admin/flash-sales/${id}`, data)
}

/** 场次添加商品（校验是平台商品） */
export function addConsoleFlashItem(
  id: number,
  data: ConsoleFlashItemSaveParams,
): Promise<ConsoleAdminFlashSaleVO> {
  return http.post<ConsoleAdminFlashSaleVO>(`/platform/admin/flash-sales/${id}/items`, data)
}

/** 场次移除商品 */
export function removeConsoleFlashItem(id: number, itemId: number): Promise<null> {
  return http.delete<null>(`/platform/admin/flash-sales/${id}/items/${itemId}`)
}

/** 场次切状态：1 预告 / 2 进行中 / 3 结束（切后重建该场次秒杀缓存） */
export function toggleConsoleFlashStatus(id: number, status: 1 | 2 | 3): Promise<null> {
  return http.post<null>(`/platform/admin/flash-sales/${id}/status`, undefined, { params: { status } })
}

/* ------------------------------------------------------------------ *
 * 3.5 平台订单
 * ------------------------------------------------------------------ */

/** 平台订单分页（只含 seller_id=平台账号） */
export function fetchConsoleOrders(
  query: AdminOrderQuery,
): Promise<PageResult<ConsoleAdminOrderVO>> {
  return http.get<PageResult<ConsoleAdminOrderVO>>('/platform/admin/orders', query)
}

/* ------------------------------------------------------------------ *
 * 3.6 缓存运维
 * ------------------------------------------------------------------ */

/** 缓存统计（只读扫描 `ershou:platform:*`） */
export function fetchConsoleCacheStats(): Promise<ConsoleCacheStatsVO> {
  return http.get<ConsoleCacheStatsVO>('/platform/admin/cache/stats')
}

/** 立即重建券 / 秒杀缓存（返回重建条目数，后端可能返回 null） */
export function refreshConsoleCache(): Promise<number | null> {
  return http.post<number | null>('/platform/admin/cache/refresh')
}
