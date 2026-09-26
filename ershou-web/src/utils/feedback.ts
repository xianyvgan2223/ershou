import { ElMessage } from 'element-plus'
import type { MessageHandler } from 'element-plus'

type TipType = 'success' | 'warning' | 'info' | 'error'

/** 操作提示的展示时长（毫秒） */
const TIP_DURATION = 1600

let activeTip: MessageHandler | null = null
let closeTimer: number | undefined

/**
 * 统一的轻提示（收藏、加购这类操作反馈）。
 *
 * 直接用 ElMessage 默认行为有两个坑：
 * 1. 鼠标停在提示上会暂停倒计时，移开后仍可能长时间留在页面上；
 * 2. 连续操作会堆叠出多条相同提示。
 * 这里改成「同一时刻只保留一条 + 自己定时强制关闭」，保证提示一定会消失。
 */
export function showTip(message: string, type: TipType = 'success'): MessageHandler {
  activeTip?.close()
  if (closeTimer !== undefined) window.clearTimeout(closeTimer)

  // duration: 0 关闭组件自身的倒计时，改由下面的定时器强制关闭，避免被 hover 暂停卡住
  const handler = ElMessage({ type, message, duration: 0 })
  activeTip = handler

  closeTimer = window.setTimeout(() => {
    handler.close()
    if (activeTip === handler) activeTip = null
    closeTimer = undefined
  }, TIP_DURATION)

  return handler
}
