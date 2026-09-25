/** 金额展示：整数不带小数，非整数保留两位 */
export function formatPrice(value?: number | null): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '--'
  const num = Number(value)
  return Number.isInteger(num) ? String(num) : num.toFixed(2)
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

/** 商品状态：1 在售 / 2 已售 / 3 已下架（后端未下发 statusText，这里做兜底映射） */
const ITEM_STATUS_TEXT: Record<number, string> = {
  0: '待审核',
  1: '在售',
  2: '已售',
  3: '已下架',
}

export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export function itemStatusLabel(status?: number | null): string {
  if (status === null || status === undefined) return '未知'
  return ITEM_STATUS_TEXT[status] ?? `状态 ${status}`
}

export function itemStatusTagType(status?: number | null): TagType {
  switch (status) {
    case 1:
      return 'success'
    case 2:
      return 'info'
    case 3:
      return 'warning'
    case 0:
      return 'primary'
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

/** 取昵称首字，用于没有头像时的占位 */
export function nameInitial(name?: string | null): string {
  if (!name) return '同'
  return name.trim().charAt(0).toUpperCase()
}
