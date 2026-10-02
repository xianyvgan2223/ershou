import { ElMessage } from 'element-plus'
import type { MessageHandler } from 'element-plus'

type TipType = 'success' | 'warning' | 'info' | 'error'

/** 操作提示的展示时长（毫秒） */
const TIP_DURATION = 1600

/**
 * 我们自己弹过的所有提示。
 *
 * 以前只记住**最后一条**（单个 activeTip）。问题是页面里还会用原生
 * `ElMessage.success(...)`（Element Plus 默认 3 秒、且**鼠标停在上面会暂停倒计时**），
 * 两种混用时早先那条就没人负责关闭了 —— 表现就是"点了按钮，气泡有时候不自己消失"。
 * 改成记住全部，每次弹新提示前先把旧的都收掉。
 */
const openTips = new Set<MessageHandler>()
let closeTimer: number | undefined

/**
 * 统一的轻提示（审核、收藏、加购这类操作反馈）。
 *
 * 直接用 ElMessage 默认行为有两个坑：
 * 1. 鼠标停在提示上会暂停倒计时，移开后仍可能长时间留在页面上；
 * 2. 连续操作会堆叠出多条相同提示。
 * 这里改成「同一时刻只保留一条 + 自己定时强制关闭」，保证提示一定会消失。
 */
export function showTip(message: string, type: TipType = 'success'): MessageHandler {
  closeAllTips()
  if (closeTimer !== undefined) window.clearTimeout(closeTimer)

  // duration: 0 关闭组件自身的倒计时，改由下面的定时器强制关闭，避免被 hover 暂停卡住
  const handler = ElMessage({ type, message, duration: 0 })
  openTips.add(handler)

  closeTimer = window.setTimeout(() => {
    handler.close()
    openTips.delete(handler)
    closeTimer = undefined
  }, TIP_DURATION)

  return handler
}

/** 立刻收掉所有还在的提示（离开页面 / 切换视图时调，避免气泡跟着用户跑到下一个页面） */
export function closeAllTips(): void {
  openTips.forEach((tip) => tip.close())
  openTips.clear()
  if (closeTimer !== undefined) {
    window.clearTimeout(closeTimer)
    closeTimer = undefined
  }
}

/**
 * 需要更长阅读时间的提示（比如"有违规商品申请重新上架"）。
 *
 * 它和 showTip 的区别只是时长，但**同样是自己定时强制关闭** —— 不能退回 ElMessage 默认行为，
 * 否则鼠标停上去就会暂停倒计时，气泡又不走了。
 */
const LONG_TIP_DURATION = 8000

export function showLongTip(message: string, type: TipType = 'info'): MessageHandler {
  closeAllTips()
  const handler = ElMessage({ type, message, duration: 0 })
  openTips.add(handler)
  closeTimer = window.setTimeout(() => {
    handler.close()
    openTips.delete(handler)
    closeTimer = undefined
  }, LONG_TIP_DURATION)
  return handler
}
