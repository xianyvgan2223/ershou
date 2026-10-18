import { http } from '@/utils/request'
import type { PageQuery, PageResult } from './types'

/**
 * 平台代卖（官方严选）接口与类型。
 *
 * 契约：`智能体团队/第2轮-平台代卖-设计.md` 第 3.2 / 3.3 / 3.4 节。
 *
 * 约定（重要，后续改动请沿用）：
 * - **平台相关类型只放这一个文件**，不往 `api/types.ts` 里塞（那边是全站公共类型）。
 * - 接口返回值已被 `utils/request.ts` 拦截器解包成业务 data，这里直接给业务类型。
 * - 公开读接口（`/platform/shop/**`）在后端白名单里，**不需要登录**；
 *   领券 / 抢购 / 直购（`/platform/me/**`）与后台（`/platform/admin/**`）需要登录。
 */

/* ------------------------------------------------------------------ *
 * 通用
 * ------------------------------------------------------------------ */

/** 平台商品排序：最新 / 价格升序 / 价格降序（与 `GET /items` 的 sort 枚举一致） */
export type PlatformSort = 'latest' | 'price_asc' | 'price_desc'

/** 平台官方卖家账号 id（后端 `ershou.platform.seller-id` 默认值，前端只用于展示兜底） */
export const PLATFORM_SELLER_ID = 1000

/** 平台卖家展示名（后端会下发真实昵称，这里只做兜底） */
export const PLATFORM_SELLER_NAME = '校园二手官方严选'

/** 平台代卖点状态：1 启用 / 0 停用 */
export type PlatformPointStatus = 0 | 1

/* ------------------------------------------------------------------ *
 * 3.2 公开读接口
 * ------------------------------------------------------------------ */

/** 专区概览：`GET /platform/shop/overview` */
export interface PlatformOverviewVO {
  /** 在售平台商品数 */
  platformItemCount: number
  /** 启用中的代卖/自提点数 */
  pointCount: number
  /** 可领券数（未领完、进行中） */
  couponCount: number
  /** 当前进行中的秒杀场次 id；无场次为 null */
  flashSaleId: number | null
  flashSaleTitle: string | null
  /** 秒杀场次状态：1 预告 / 2 进行中 / 3 结束；无场次为 null */
  flashSaleStatus: number | null
}

/** 代卖/自提点：`GET /platform/shop/points`、后台 `GET/POST/PUT /platform/admin/points` */
export interface PlatformPointVO {
  id: number
  campusId: number
  /** 校区名（后端可能不下发，展示时兜底取当前用户校区名） */
  campusName?: string | null
  name: string
  address: string
  /** GCJ-02 经纬度；可能为空（纯展示地址） */
  lng: number | null
  lat: number | null
  /** 营业时间，例如 `09:00-21:00` */
  openHours?: string | null
  contactPhone?: string | null
  /** 到点指引 */
  guideText?: string | null
  coverUrl?: string | null
  /** 排序：小的靠前 */
  sort: number
  /** 1 启用 / 0 停用（后台软删也是 0） */
  status: number
  /** 该点位挂着的平台商品数（后端可选下发） */
  itemCount?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/**
 * 平台商品：`GET /platform/shop/items`。
 *
 * 注意它与 `api/item.ts` 的 `ItemVO` **不是同一个类型**（后端是 `PlatformItemVO`）：
 * 少了可议价/收藏/卖家对象等个人商品字段，多了秒杀价与秒杀库存。
 * 页面要复用 `components/ItemCard.vue` 时，用 `toCardItem()` 适配一次。
 */
export interface PlatformItemVO {
  id: number
  title: string
  description?: string | null
  price: number
  originalPrice?: number | null
  coverUrl: string
  images?: string[]
  categoryId: number
  categoryName?: string | null
  conditionLevel: number
  stock?: number | null
  /** 与个人商品同一套状态码：2 在售 / 3 已锁定 / 4 已售出 … */
  status: number
  statusText?: string | null
  publishedAt: string
  /** 平台代卖点 id / 名称（对应 `item.platform_point_id`） */
  pointId?: number | null
  pointName?: string | null
  /** 固定 2（平台） */
  sellerType?: 1 | 2
  /** 秒杀价 / 秒杀剩余库存：非秒杀商品为 null */
  seckillPrice?: number | null
  seckillStock?: number | null
}

/** 平台商品列表查询参数 */
export interface PlatformItemQuery extends PageQuery {
  campusId: number
  /** 按代卖点筛选（hero 下方 chips） */
  pointId?: number
  categoryId?: number
  sort?: PlatformSort
}

/** 优惠券类型：1 满减 / 2 折扣 */
export type CouponType = 1 | 2

/** 优惠券：`GET /platform/shop/coupons` */
export interface CouponVO {
  id: number
  name: string
  type: CouponType
  /** 满减门槛（type=1） */
  thresholdAmount?: number | null
  /** 减免金额（type=1） */
  discountAmount?: number | null
  /** 折扣率（type=2），例如 0.9 表示 9 折；后端也可能下发 90，展示用 `couponRateText` 兼容 */
  discountRate?: number | null
  /** 每人限领张数 */
  perUserLimit: number
  startAt: string
  endAt: string
  status: number
  totalStock: number
  /** 剩余库存（实时读 Redis） */
  leftStock: number
  /** 当前用户是否已领（未登录恒 false） */
  claimed: boolean
  /** 当前是否可以领取（未开始 / 已结束 / 领完 / 已领 / 未登录都由前端结合 claimed 再判一次） */
  claimable: boolean
}

/** 秒杀场次商品：`GET /platform/shop/flash-sale` 里的 items[] */
export interface FlashSaleItemVO {
  itemId: number
  title: string
  coverUrl: string
  /** 原价（划线价） */
  originPrice: number
  /** 秒杀价 */
  seckillPrice: number
  /** 秒杀总库存 */
  stock: number
  /** 剩余库存 */
  leftStock: number
  soldCount: number
  perUserLimit: number
  /** 当前用户是否已抢 */
  grabbed: boolean
}

/** 秒杀场次：`GET /platform/shop/flash-sale`；无进行中场次时接口返回 null */
export interface FlashSaleVO {
  id: number
  title: string
  startAt: string
  endAt: string
  /** 1 预告 / 2 进行中 / 3 结束 */
  status: number
  /** 距结束剩余秒数（服务端算好，前端只做本地倒计时递减） */
  remainSeconds: number
  items: FlashSaleItemVO[]
}

/* ------------------------------------------------------------------ *
 * 3.3 登录用户动作接口
 * ------------------------------------------------------------------ */

/**
 * 抢券结果：`POST /platform/me/coupons/{id}/claim`。
 *
 * code：`1 成功 / 0 领完 / -1 未预热 / -2 超限 / -3 未开始或已结束 / -4 限流`。
 * 注意这里的 `code` 是**业务子码**（不是 HTTP/Result.code）：抢券失败时接口通常也返回
 * 成功响应，由前端按子码给文案，所以不要用 try/catch 当唯一分支。
 */
export interface CouponClaimVO {
  success: boolean
  code: number
  message: string
  /** 领取后的剩余库存（便于直接刷新按钮上的数字） */
  leftStock: number
}

/** 我的券：`GET /platform/me/coupons`（DB 为准，含状态） */
export interface MyCouponVO {
  /** 领取记录 id（`platform_coupon_user.id`） */
  id: number
  couponId: number
  name: string
  type: CouponType
  thresholdAmount?: number | null
  discountAmount?: number | null
  discountRate?: number | null
  /** 1 未用 / 2 已用 / 3 过期 */
  status: number
  startAt: string
  endAt: string
  receivedAt: string
  usedAt?: string | null
  orderId?: number | null
}

/** 抢购结果：`POST /platform/me/flash-sale/{itemId}/grab`（code 含义同抢券） */
export interface GrabVO {
  success: boolean
  code: number
  message: string
  orderNo?: string | null
  orderId?: number | null
}

/** 平台商品直购：`POST /platform/me/items/{id}/buy` 的入参 */
export interface PlatformBuyParams {
  /** 数量，默认 1（后端会夹取 ≥1） */
  quantity?: number
  /** 优惠券 id：**本轮后端不抵扣**，仅预留（设计第 8 节） */
  couponId?: number | null
}

/** 平台下单结果（`OrderService.platformBuy` 的返回） */
export interface PlatformBuyVO {
  orderId: number
  orderNo: string
  itemId: number
  /** 成交价（秒杀价或商品价） */
  dealPrice: number
  pointId?: number | null
  itemTitle?: string | null
  itemCover?: string | null
}

/* ------------------------------------------------------------------ *
 * 3.4 管理员接口（代卖点 CRUD / 平台商品发布 / 缓存刷新）
 * ------------------------------------------------------------------ */

/** 后台新建 / 编辑代卖点的入参（`POST/PUT /platform/admin/points`） */
export interface PlatformPointSaveParams {
  campusId: number
  name: string
  address: string
  lng?: number | null
  lat?: number | null
  openHours?: string | null
  contactPhone?: string | null
  guideText?: string | null
  coverUrl?: string | null
  sort?: number
  /** 1 启用 / 0 停用，默认 1 */
  status?: PlatformPointStatus
}

/** 后台发布平台商品的入参（`POST /platform/admin/items`） */
export interface PlatformItemPublishParams {
  title: string
  description?: string
  categoryId: number
  conditionLevel: number
  price: number
  originalPrice?: number | null
  stock?: number
  images: string[]
  /** 代卖点 id（必填，后端校验必须属于同校区） */
  platformPointId: number
  /** 校区 id，可选：后端默认取代卖点所属校区 */
  campusId?: number
}

/** 后台刷新券/秒杀 Redis 缓存的结果：返回重建的缓存条目数（后端也可能返回 null） */
export type PlatformCacheRefreshResult = number | null

/* ------------------------------------------------------------------ *
 * 3.2 公开读接口函数
 * ------------------------------------------------------------------ */

/** 专区概览（hero 上的统计数字） */
export function fetchPlatformOverview(campusId: number): Promise<PlatformOverviewVO | null> {
  return http.get<PlatformOverviewVO | null>('/platform/shop/overview', { campusId })
}

/** 代卖/自提点列表（只返回 status=1，按 sort,id） */
export function fetchPlatformPoints(campusId: number): Promise<PlatformPointVO[]> {
  return http.get<PlatformPointVO[]>('/platform/shop/points', { campusId })
}

/** 平台商品分页（只返回 seller_type=2 且 status=2） */
export function fetchPlatformItems(query: PlatformItemQuery): Promise<PageResult<PlatformItemVO>> {
  return http.get<PageResult<PlatformItemVO>>('/platform/shop/items', query)
}

/** 优惠券列表（含实时 leftStock 与当前用户 claimed） */
export function fetchPlatformCoupons(campusId: number): Promise<CouponVO[]> {
  return http.get<CouponVO[]>('/platform/shop/coupons', { campusId })
}

/** 当前进行中的秒杀场次；**无场次时后端返回 null** */
export function fetchPlatformFlashSale(campusId: number): Promise<FlashSaleVO | null> {
  return http.get<FlashSaleVO | null>('/platform/shop/flash-sale', { campusId })
}

/* ------------------------------------------------------------------ *
 * 3.3 登录用户动作接口函数
 * ------------------------------------------------------------------ */

/** 抢券（Lua 原子扣减；失败也返回 200，按返回的 code 给文案） */
export function claimPlatformCoupon(couponId: number): Promise<CouponClaimVO> {
  return http.post<CouponClaimVO>(`/platform/me/coupons/${couponId}/claim`)
}

/** 我的券列表（未登录会 401，由拦截器统一跳登录） */
export function fetchMyPlatformCoupons(): Promise<MyCouponVO[]> {
  return http.get<MyCouponVO[]>('/platform/me/coupons')
}

/** 秒杀抢购（成功即生成平台面交单；失败按返回的 code 给文案） */
export function grabFlashSaleItem(itemId: number): Promise<GrabVO> {
  return http.post<GrabVO>(`/platform/me/flash-sale/${itemId}/grab`)
}

/** 平台商品直购（quantity 默认 1；couponId 本轮不抵扣） */
export function buyPlatformItem(
  itemId: number,
  params: PlatformBuyParams = {},
): Promise<PlatformBuyVO> {
  return http.post<PlatformBuyVO>(`/platform/me/items/${itemId}/buy`, {
    quantity: params.quantity ?? 1,
    couponId: params.couponId ?? null,
  })
}

/* ------------------------------------------------------------------ *
 * 3.4 管理员接口函数
 *
 * ⚠️ 函数名已与 lead / admin-member 对齐，**改名要同步通知**（后台代卖点页直接 import）。
 * ------------------------------------------------------------------ */

/** 后台：全部代卖点（含停用），campusId 不传表示全部校区 */
export function getAdminPoints(campusId?: number): Promise<PlatformPointVO[]> {
  return http.get<PlatformPointVO[]>('/platform/admin/points', campusId ? { campusId } : undefined)
}

/** 后台：新建代卖点 */
export function saveAdminPoint(data: PlatformPointSaveParams): Promise<PlatformPointVO> {
  return http.post<PlatformPointVO>('/platform/admin/points', data)
}

/** 后台：编辑代卖点 */
export function updateAdminPoint(
  id: number,
  data: PlatformPointSaveParams,
): Promise<PlatformPointVO> {
  return http.put<PlatformPointVO>(`/platform/admin/points/${id}`, data)
}

/** 后台：删除代卖点（后端为**软删** status=0，历史商品引用不破坏） */
export function deleteAdminPoint(id: number): Promise<null> {
  return http.delete<null>(`/platform/admin/points/${id}`)
}

/** 后台：启停代卖点（status=1 启用 / 0 停用） */
export function toggleAdminPointStatus(
  id: number,
  status: PlatformPointStatus,
): Promise<null> {
  return http.post<null>(`/platform/admin/points/${id}/status`, undefined, { params: { status } })
}

/** 后台：发布平台商品（seller_id=1000、seller_type=2、免审核、有效期 30 天） */
export function publishPlatformItem(data: PlatformItemPublishParams): Promise<PlatformItemVO> {
  return http.post<PlatformItemVO>('/platform/admin/items', data)
}

/** 后台：重建券 / 秒杀 Redis 缓存（预热；返回重建条目数） */
export function refreshPlatformCache(): Promise<PlatformCacheRefreshResult> {
  return http.post<PlatformCacheRefreshResult>('/platform/admin/cache/refresh')
}

/* ------------------------------------------------------------------ *
 * 展示辅助（纯前端，无请求）
 * ------------------------------------------------------------------ */

/**
 * 优惠券门槛/优惠文案：`满 50 减 8` / `满 100 打 9 折`。
 *
 * `discountRate` 后端可能下发 `0.9` 或 `90`，这里统一归一成"几折"避免展示成 `0.9 折`。
 */
export function couponRuleText(coupon: {
  type: CouponType | number
  thresholdAmount?: number | null
  discountAmount?: number | null
  discountRate?: number | null
}): string {
  const threshold = Number(coupon.thresholdAmount ?? 0)
  const thresholdText = threshold > 0 ? `满 ${formatAmount(threshold)}` : '无门槛'
  if (Number(coupon.type) === 1) {
    const amount = Number(coupon.discountAmount ?? 0)
    return amount > 0 ? `${thresholdText} 减 ${formatAmount(amount)}` : thresholdText
  }
  const raw = Number(coupon.discountRate ?? 0)
  if (!raw) return `${thresholdText} 折扣`
  // 0.9 → 9 折；90 → 9 折；0.85 → 8.5 折
  const rate = raw > 1 ? raw / 10 : raw * 10
  return `${thresholdText} 打 ${formatAmount(rate)} 折`
}

/** 优惠券面额主文案：满减显示 `¥8`，折扣显示 `9折` */
export function couponFaceValue(coupon: {
  type: CouponType | number
  discountAmount?: number | null
  discountRate?: number | null
}): string {
  if (Number(coupon.type) === 1) {
    return `¥${formatAmount(Number(coupon.discountAmount ?? 0))}`
  }
  const raw = Number(coupon.discountRate ?? 0)
  const rate = raw > 1 ? raw / 10 : raw * 10
  return `${formatAmount(rate)}折`
}

/** 金额/数字展示：整数不带小数，非整数最多两位（与 `utils/format.ts#formatPrice` 口径一致） */
function formatAmount(value: number): string {
  if (!Number.isFinite(value)) return '--'
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

/** 秒杀库存进度百分比（0~100，供进度条宽度用） */
export function stockPercent(leftStock?: number | null, stock?: number | null): number {
  const total = Number(stock ?? 0)
  if (!total || total <= 0) return 0
  const left = Math.max(0, Math.min(Number(leftStock ?? 0), total))
  return Math.round(((total - left) / total) * 100)
}

/**
 * `PlatformItemVO` → `ItemVO` 的适配（只为了复用 `components/ItemCard.vue`）。
 *
 * 平台商品没有个人商品的可议价/收藏/浏览量等字段，这里给安全默认值；
 * `sellerType: 2` + 官方昵称让卡片自己渲染「官方代卖」角标与「官方严选」卖家行。
 */
export function toCardItem(item: PlatformItemVO, campusId: number): import('./item').ItemVO {
  return {
    id: item.id,
    title: item.title,
    price: item.seckillPrice ?? item.price,
    originalPrice: item.originalPrice ?? null,
    negotiable: 0,
    conditionLevel: item.conditionLevel,
    coverUrl: item.coverUrl || item.images?.[0] || '',
    images: item.images ?? [],
    categoryId: item.categoryId,
    categoryName: item.categoryName ?? '',
    campusId,
    preferPointId: item.pointId ?? null,
    preferPointName: item.pointName ?? null,
    statusText: item.statusText ?? undefined,
    status: item.status,
    viewCount: 0,
    favCount: 0,
    publishedAt: item.publishedAt,
    seller: {
      id: PLATFORM_SELLER_ID,
      nickname: PLATFORM_SELLER_NAME,
      avatar: '',
      creditScore: 0,
      identityFlag: 1,
      shopName: '官方严选',
    },
    sellerType: 2,
    platformPointId: item.pointId ?? null,
  }
}
