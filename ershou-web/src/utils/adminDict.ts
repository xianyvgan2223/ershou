/** 运营后台的文案词典与展示助手（概览页 / 日志页 / 面交单页共用） */
import type { AuditLogItem } from '@/api/admin'
import type { TagType } from './format'

export const MODULE_TEXT: Record<string, string> = {
  item: '商品',
  point: '点位',
  user: '用户',
}

export const MODULE_OPTIONS = [
  { label: '商品', value: 'item' },
  { label: '点位', value: 'point' },
  { label: '用户', value: 'user' },
]

export const ACTION_TEXT: Record<string, string> = {
  approve: '审核通过',
  reject: '审核驳回',
  'batch-approve': '批量通过',
  'batch-reject': '批量驳回',
  'force-offline': '强制下架',
  'point-offline': '下架点位',
  'point-online': '恢复点位',
  'update-status': '变更用户状态',
  'adjust-credit': '调整信用分',
}

/** 日志页动作下拉：key 必须与后端 AuditLogService.record 写入的 action 完全一致 */
export const ACTION_OPTIONS = Object.keys(ACTION_TEXT).map((value) => ({
  label: ACTION_TEXT[value],
  value,
}))

/** 动作标签色：通过/恢复类绿、驳回/下架类红、用户类蓝 */
export function actionTagType(action?: string): TagType {
  if (!action) return 'info'
  if (action === 'approve' || action === 'batch-approve' || action === 'point-online') return 'success'
  if (action.includes('reject') || action.includes('offline')) return 'danger'
  if (action === 'adjust-credit') return 'warning'
  return 'primary'
}

export function moduleText(module?: string): string {
  if (!module) return '—'
  return MODULE_TEXT[module] ?? module
}

export function actionText(action?: string): string {
  if (!action) return '—'
  return ACTION_TEXT[action] ?? action
}

const TARGET_TEXT: Record<string, string> = {
  item: '商品',
  trade_point: '点位',
  user: '用户',
}

export function targetText(log: AuditLogItem): string {
  const type = log.targetType ? (TARGET_TEXT[log.targetType] ?? log.targetType) : '—'
  return type + ' #' + (log.targetId ?? '—')
}

/** 审计日志的前后值：对象转紧凑 JSON，空值给占位符 */
export function formatJson(value: unknown): string {
  if (value === null || value === undefined) return '—'
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

/** 详情弹窗里的缩进 JSON：逐行展示才好读 */
export function formatJsonPretty(value: unknown): string {
  if (value === null || value === undefined) return '—'
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/**
 * 面交单状态的字典与取色统一放在 `utils/format`（`ORDER_STATUS` / `ORDER_STATUS_OPTIONS` /
 * `orderStatusTagTypeByCode`），后台与学生端共用一份，这里不再重复定义。
 */
export { ORDER_STATUS, ORDER_STATUS_OPTIONS, orderStatusTagTypeByCode } from './format'
