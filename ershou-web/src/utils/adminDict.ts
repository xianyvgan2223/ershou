/** 运营后台的文案词典与展示助手（概览页 / 日志页共用） */
import type { AuditLogItem } from '@/api/admin'
import type { TagType } from './format'

export const MODULE_TEXT: Record<string, string> = {
  item: '商品',
  point: '点位',
  user: '用户',
}

export const ACTION_TEXT: Record<string, string> = {
  approve: '审核通过',
  reject: '审核驳回',
  'batch-approve': '批量通过',
  'batch-reject': '批量驳回',
  'force-offline': '强制下架',
  'update-status': '变更用户状态',
  'adjust-credit': '调整信用分',
}

/** 动作标签色：通过类绿、驳回/下架类红、用户类蓝 */
export function actionTagType(action?: string): TagType {
  if (!action) return 'info'
  if (action.startsWith('approve') || action === 'batch-approve') return 'success'
  if (action.includes('reject') || action === 'force-offline') return 'danger'
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
