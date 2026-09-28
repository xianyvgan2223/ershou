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
 * 商品状态枚举 —— **以数据库 item.status 为准**：
 * 1 审核中 / 2 在售 / 3 已锁定（有人约了面交）/ 4 已售出 / 5 已下架 / 6 违规下架。
 *
 * 历史坑：这里曾经写成「1 在售 / 2 已售 / 3 已下架」，和 Detail/Cart 里用的 status===2（在售）
 * 自相矛盾，导致「我的发布」三个 tab 全部错位（在售的商品被显示成已售）。
 * 所有 status 判断请统一用 ITEM_STATUS 里的常量，别再写字面量。
 */
export const ITEM_STATUS = {
  REVIEWING: 1,
  ON_SALE: 2,
  LOCKED: 3,
  SOLD: 4,
  OFFLINE: 5,
  VIOLATION: 6,
} as const

const ITEM_STATUS_TEXT: Record<number, string> = {
  0: '待审核',
  1: '审核中',
  2: '在售',
  3: '已锁定',
  4: '已售出',
  5: '已下架',
  6: '违规下架',
}

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
    case ITEM_STATUS.SOLD:
    case ITEM_STATUS.OFFLINE:
      return 'info'
    case ITEM_STATUS.REVIEWING:
      return 'primary'
    case ITEM_STATUS.VIOLATION:
      return 'danger'
    default:
      return 'info'
  }
}

/** 订单状态标签色（后端 statusText 已中文化，这里按关键字取色） */
export function orderStatusTagType(statusText?: string | null): TagType {
  const text = statusText ?? ''
  if (text.includes('完成')) return 'success'
  if (text.includes('拒绝') || text.includes('取消')) return 'info'
  if (text.includes('待')) return 'warning'
  return 'primary'
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
