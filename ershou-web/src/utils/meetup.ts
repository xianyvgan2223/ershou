/**
 * 面交时间窗工具：前端与后端 OrderService.validateSlot 的规则保持一致
 * （开始时间不早于当前、结束晚于开始、单次不超过 3 小时、最多预约 7 天内）。
 * 前端先拦一道，避免用户填完才吃一个 40005。
 */

/** 单次面交时间窗上限（小时），与后端 MAX_SLOT_MINUTES 一致 */
export const MEETUP_MAX_HOURS = 3
/** 最多可提前预约的天数 */
export const MEETUP_MAX_DAYS = 7

/** 'yyyy-MM-dd HH:mm:ss'（后端 DateTimeFormatter 要求的格式） */
export function formatMeetupTime(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** 兼容 'yyyy-MM-dd HH:mm:ss' 与 ISO：Safari/部分环境不认空格分隔，手动换成 T */
export function parseMeetupTime(value?: string | null): Date | null {
  if (!value) return null
  const date = new Date(value.replace(' ', 'T'))
  return Number.isNaN(date.getTime()) ? null : date
}

/** 向上取整到下一个整/半点（面交时间看起来更规整） */
function roundUpToHalfHour(date: Date): Date {
  const next = new Date(date)
  next.setSeconds(0, 0)
  const minutes = next.getMinutes()
  if (minutes === 0 || minutes === 30) return next
  next.setMinutes(minutes < 30 ? 30 : 60)
  return next
}

/**
 * 默认时间窗：1 小时后 ~ 2 小时后。
 * 之前默认是空的，用户不点时间就直接提交 → 40005；点了又常常填成过去时间。
 */
export function defaultMeetupRange(): [string, string] {
  const start = roundUpToHalfHour(new Date(Date.now() + 60 * 60 * 1000))
  const end = new Date(start.getTime() + 60 * 60 * 1000)
  return [formatMeetupTime(start), formatMeetupTime(end)]
}

/** 时间窗时长文案：'1 小时 30 分' */
export function formatMeetupDuration(range?: string[] | null): string {
  const start = parseMeetupTime(range?.[0])
  const end = parseMeetupTime(range?.[1])
  if (!start || !end || end.getTime() <= start.getTime()) return ''
  const minutes = Math.round((end.getTime() - start.getTime()) / 60000)
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  if (!hours) return `${rest} 分钟`
  return rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
}

/**
 * 校验时间窗：通过返回 null，否则返回给用户看的提示。
 * 时间窗是<b>可选</b>的：两边都留空表示"待协商"（合法）；只填一边、或填了但越界才报错。
 */
export function validateMeetupRange(range?: string[] | null): string | null {
  const [rawStart, rawEnd] = range ?? []
  if (!rawStart && !rawEnd) return null
  if (!rawStart || !rawEnd) return '时间窗要么都填、要么都留空（留空表示和卖家商量）'
  const start = parseMeetupTime(rawStart)
  const end = parseMeetupTime(rawEnd)
  if (!start || !end) return '时间格式不对，请重新选择'
  if (end.getTime() <= start.getTime()) return '结束时间需晚于开始时间'
  if (end.getTime() - start.getTime() > MEETUP_MAX_HOURS * 3600 * 1000) {
    return `单次面交时间窗不能超过 ${MEETUP_MAX_HOURS} 小时`
  }
  // 后端允许 1 分钟内的偏差（validateSlot 用 minusMinutes(1) 兜底），前端同步放宽
  if (start.getTime() < Date.now() - 60 * 1000) return '开始时间不能早于当前时间'
  if (start.getTime() > Date.now() + MEETUP_MAX_DAYS * 86400 * 1000) {
    return `最多只能预约 ${MEETUP_MAX_DAYS} 天内的面交`
  }
  return null
}

/** 日期选择器：禁用今天之前与 7 天之后的日期 */
export function disabledMeetupDate(date: Date): boolean {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  if (date.getTime() < today.getTime()) return true
  const limit = new Date(today)
  limit.setDate(limit.getDate() + MEETUP_MAX_DAYS)
  limit.setHours(23, 59, 59, 999)
  return date.getTime() > limit.getTime()
}
