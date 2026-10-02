/** 金额展示：整数不带小数，非整数保留两位 */
export function formatPrice(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '--'
  const num = Number(value)
  return Number.isInteger(num) ? String(num) : num.toFixed(2)
}

/** 计数展示（浏览量 / 收藏数）：满 1 万折算成「x.x万」，避免卡片被长数字撑开 */
export function formatCount(value?: number | null): string {
  const num = Number(value ?? 0)
  if (!Number.isFinite(num) || num <= 0) return '0'
  if (num < 10_000) return String(Math.floor(num))
  return (num / 10_000).toFixed(1).replace(/\.0$/, '') + '万'
}

/** 成色文案：1 全新 … 5 较重使用痕迹，其余按「N 成新」兜底 */
const CONDITION_TEXT: Record<number, string> = {
  1: '全新',
  2: '几乎全新',
  3: '轻微使用痕迹',
  4: '明显使用痕迹',
  5: '较重使用痕迹',
}

export function conditionLabel(level?: number | null): string {
  if (level === null || level === undefined) return '成色未知'
  return CONDITION_TEXT[level] ?? `${level} 成新`
}

/**
 * 商品状态枚举 —— **以数据库 `item.status` 注释为准**（见 sql/schema.sql）：
 * 0 草稿 / 1 审核中 / 2 在售 / 3 已锁定（有人约了面交）/ 4 已售出 / 5 已下架（卖家自己下架）
 * / 6 审核未通过（内容违规被运营驳回，卖家可改后重发）/ 7 已过期 / 8 违规下架（运营强制下架）。
 *
 * 历史坑 1：这里曾经写成「1 在售 / 2 已售 / 3 已下架」，和 Detail/Cart 里用的 status===2（在售）
 * 自相矛盾，导致「我的发布」三个 tab 全部错位（在售的商品被显示成已售）。
 * 历史坑 2：`VIOLATION` 曾经错标成 6，把「审核驳回」和「违规下架」混成一个状态，
 * 于是被驳回的商品在卖家眼里显示成「违规下架」。6 与 8 是两件事，别再合并。
 * 所有 status 判断请统一用 ITEM_STATUS 里的常量，别再写字面量。
 */
export const ITEM_STATUS = {
  DRAFT: 0,
  REVIEWING: 1,
  ON_SALE: 2,
  LOCKED: 3,
  SOLD: 4,
  OFFLINE: 5,
  REJECTED: 6,
  EXPIRED: 7,
  VIOLATION: 8,
} as const

const ITEM_STATUS_TEXT: Record<number, string> = {
  0: '草稿',
  1: '审核中',
  2: '在售',
  3: '已锁定',
  4: '已售出',
  5: '已下架',
  6: '审核未通过',
  7: '已过期',
  8: '违规下架',
}

/** 后台筛选用：按 status 从小到大排好的下拉选项（文案与 itemStatusLabel 同一份字典） */
export const ITEM_STATUS_OPTIONS: { label: string; value: number }[] = Object.keys(ITEM_STATUS_TEXT)
  .map(Number)
  .sort((a, b) => a - b)
  .map((value) => ({ label: ITEM_STATUS_TEXT[value], value }))

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export function itemStatusLabel(status?: number | null): string {
  if (status === null || status === undefined) return '未知'
  return ITEM_STATUS_TEXT[status] ?? `状态 ${status}`
}

export function itemStatusTagType(status?: number | null): TagType {
  switch (status) {
    case ITEM_STATUS.ON_SALE:
      return 'success'
    case ITEM_STATUS.LOCKED:
      return 'warning'
    case ITEM_STATUS.REVIEWING:
      return 'primary'
    case ITEM_STATUS.REJECTED:
    case ITEM_STATUS.VIOLATION:
      return 'danger'
    // 草稿 / 已售出 / 已下架 / 已过期：都是「不需要运营或卖家再动手」的终态或暂存态
    default:
      return 'info'
  }
}

/**
 * 面交单状态 —— 与后端 `OrderService.statusText` 保持一致。
 *
 * 颜色/文案都从这里派生，别再按 `statusText` 关键字猜色：
 * 关键字匹配漏一个词就会静默落到默认色（"已失效" 曾经因此被渲染成品牌绿，
 * 一个作废的单子看起来像好消息）。
 */
export const ORDER_STATUS = {
  PENDING_SELLER: 1,
  WAITING: 2,
  DONE: 3,
  CANCELLED: 4,
  INVALID: 5,
  NO_SHOW: 6,
  DISPUTE: 7,
} as const

const ORDER_STATUS_TEXT: Record<number, string> = {
  1: '待卖家确认',
  2: '待面交',
  3: '已完成',
  4: '已取消',
  5: '已失效',
  6: '爽约',
  7: '纠纷中',
}

/** 后台筛选用：与 orderStatusLabel 同一份字典 */
export const ORDER_STATUS_OPTIONS: { label: string; value: number }[] = Object.keys(ORDER_STATUS_TEXT)
  .map(Number)
  .sort((a, b) => a - b)
  .map((value) => ({ label: ORDER_STATUS_TEXT[value], value }))

export function orderStatusLabel(status?: number | null): string {
  if (status === null || status === undefined) return '未知'
  return ORDER_STATUS_TEXT[status] ?? `状态 ${status}`
}

/** 面交单状态标签色：完成绿、进行中橙、爽约/纠纷红、取消/失效灰 */
export function orderStatusTagTypeByCode(status?: number | null): TagType {
  switch (status) {
    case ORDER_STATUS.DONE:
      return 'success'
    case ORDER_STATUS.PENDING_SELLER:
    case ORDER_STATUS.WAITING:
      return 'warning'
    case ORDER_STATUS.NO_SHOW:
    case ORDER_STATUS.DISPUTE:
      return 'danger'
    // 已取消 / 已失效：灰掉，别让作废的单子看起来像好消息
    default:
      return 'info'
  }
}

/** 时间展示：兼容 'yyyy-MM-dd HH:mm:ss' 与 ISO 字符串 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '--'
  const normalized = value.replace('T', ' ')
  return normalized.length > 16 ? normalized.slice(0, 16) : normalized
}

/** 相对时间：刚刚 / N 分钟前 / N 小时前 / N 天前，超过 7 天回落具体时间 */
export function formatRelativeTime(value?: string | null): string {
  if (!value) return '--'
  const time = new Date(value.replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return formatDateTime(value)
  const diff = Date.now() - time
  if (diff < 0) return formatDateTime(value)
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return Math.floor(diff / 60_000) + ' 分钟前'
  if (diff < 86_400_000) return Math.floor(diff / 3_600_000) + ' 小时前'
  if (diff < 7 * 86_400_000) return Math.floor(diff / 86_400_000) + ' 天前'
  return formatDateTime(value)
}

/** 取昵称首字，用于没有头像时的占位 */
export function nameInitial(name?: string | null): string {
  if (!name) return '同'
  return name.trim().charAt(0).toUpperCase()
}

/** 距离商品到期还有多少毫秒；解析不出来返回 null */
function expireRemainMs(expireAt?: string | null): number | null {
  if (!expireAt) return null
  const time = new Date(expireAt.replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) return null
  return time - Date.now()
}

/**
 * 商品有效期倒计时文案：`还剩 5 天` / `不足 1 天` / `已到期`。
 *
 * 发布后立刻算的话余下是 6.99 天，用 floor 会显示「还剩 6 天」，
 * 卖家会觉得"刚发就少了一天"，所以这里用 ceil。
 */
export function expireText(expireAt?: string | null): string {
  const ms = expireRemainMs(expireAt)
  if (ms === null) return ''
  if (ms <= 0) return '已到期'
  if (ms <= 86_400_000) return '不足 1 天'
  return `还剩 ${Math.ceil(ms / 86_400_000)} 天`
}

/** 是否临近到期（不足 1 天）：列表和详情用它换成警示色 */
export function expireUrgent(expireAt?: string | null): boolean {
  const ms = expireRemainMs(expireAt)
  return ms !== null && ms > 0 && ms <= 86_400_000
}
