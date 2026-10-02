import { onBeforeUnmount, ref } from 'vue'
import { getToken } from '@/utils/request'

/** 服务端推送的事件（与 AdminReviewWebSocketHandler 的协议一一对应） */
export interface ReviewQueuedEvent {
  /** 推送那一刻的待审条数（权威值，前端直接用它，不要自己 +1） */
  pending: number
  itemId?: number
  reviewTypeText?: string
  onlineReviewers?: number
}

export interface ReviewHandledEvent {
  pending: number
  itemId?: number
  onlineReviewers?: number
}

export interface ReviewViolationAppliedEvent {
  pending: number
  itemId?: number
  title?: string
  /** 审核工作台（status=1）里的待审条数，用于提示"有违规商品申请上架" */
  pendingAudit?: number
}

export interface UseAdminReviewSocketOptions {
  /** 审核员昵称，随握手带给后端，用于显示"谁在看这条" */
  nickname?: string
  /** 新商品上架 / 改名重新入队 */
  onQueued?: (data: ReviewQueuedEvent) => void
  /** 某条被（可能是别人）处置了 —— 本地把它移出列表 */
  onHandled?: (data: ReviewHandledEvent) => void
  /** 卖家提交了违规商品二次上架申请（不进本页队列，但要提示） */
  onViolationApplied?: (data: ReviewViolationAppliedEvent) => void
  /** 每次(重)连成功：调用方应重新拉一次列表，补上断线期间漏掉的事件 */
  onReconnected?: () => void
}

const HEARTBEAT_MS = 15_000
const MAX_RETRY = 8

/**
 * 上架审核的实时通道：ws://host/ws/admin/review?token=xxx
 *
 * <p>和私聊 store 用同一套生命周期约定（见 stores/chat.ts 的踩坑注释），但这里是
 * 页面级 composable，不进 Pinia —— 只有审核页需要它。
 *
 * <p>三条必须守住的行为（都是聊天那边踩过坑总结出来的）：
 * <ol>
 *   <li><b>重连要能自愈</b>：socket 不在 OPEN 态就丢弃重建，并且每次重连都重置退避计数，
 *       否则"连上 → 断 → 重试耗尽"之后就永久不再重连，只能刷新页面；</li>
 *   <li><b>心跳带看门狗</b>：超过 2 个周期没收到 pong 判定假死 → 主动断开走重连。
 *       TCP 层"连着"但僵死时 onclose 不会触发，界面会静默地不再实时；</li>
 *   <li><b>重连成功后必须重新拉一次列表</b>：断线期间的上架事件是补不回来的
 *       （推送没有持久化），靠 REST 兜底才谈得上"每条都被看到"。
 * </ol>
 */
export function useAdminReviewSocket(options: UseAdminReviewSocketOptions = {}) {
  const connected = ref(false)
  /** 连续重连失败次数，界面上给个提示用 */
  const retries = ref(0)
  const disabled = ref(false)

  let socket: WebSocket | null = null
  let heartbeatTimer: number | null = null
  let reconnectTimer: number | null = null
  let retryCount = 0
  let manualClose = false
  let lastPongAt = 0
  let everConnected = false

  function wsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const nickname = options.nickname ? `&nickname=${encodeURIComponent(options.nickname)}` : ''
    return `${proto}//${window.location.host}/ws/admin/review?token=${encodeURIComponent(getToken())}${nickname}`
  }

  function startHeartbeat(): void {
    stopHeartbeat()
    lastPongAt = Date.now()
    heartbeatTimer = window.setInterval(() => {
      try {
        if (!socket || socket.readyState !== WebSocket.OPEN) return
        socket.send(JSON.stringify({ type: 'ping' }))
        if (Date.now() - lastPongAt > HEARTBEAT_MS * 2 + 5_000) {
          connected.value = false
          try {
            socket.close()
          } catch {
            socket = null
          }
        }
      } catch {
        // 定时器回调里抛错会让后续 tick 变得不可靠，这里全部吞掉
      }
    }, HEARTBEAT_MS)
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer !== null) {
      window.clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function scheduleReconnect(): void {
    if (manualClose || retryCount >= MAX_RETRY) return
    const delay = Math.min(1000 * 2 ** retryCount, 16_000)
    retryCount += 1
    retries.value = retryCount
    reconnectTimer = window.setTimeout(() => connect(), delay)
  }

  function handleFrame(type: string, data: unknown): void {
    switch (type) {
      case 'ready':
        options.onReconnected?.()
        break
      case 'queued':
        options.onQueued?.(data as ReviewQueuedEvent)
        break
      case 'handled':
        options.onHandled?.(data as ReviewHandledEvent)
        break
      case 'violation-applied':
        options.onViolationApplied?.(data as ReviewViolationAppliedEvent)
        break
      case 'pong':
        lastPongAt = Date.now()
        break
      default:
        break
    }
  }

  function connect(): void {
    if (manualClose || disabled.value) return
    if (!getToken()) return
    if (socket) {
      const st = socket.readyState
      if (st === WebSocket.OPEN || st === WebSocket.CONNECTING) return
      socket = null
    }
    try {
      socket = new WebSocket(wsUrl())
    } catch {
      scheduleReconnect()
      return
    }
    socket.onopen = () => {
      retryCount = 0
      retries.value = 0
      connected.value = true
      startHeartbeat()
      // 首次连接也会收到 ready：此时的 onReconnected 就是"首次加载后同步一次"，
      // 对调用方来说语义一样（拉一次列表），不用区分是不是真的重连
      everConnected = true
    }
    socket.onmessage = (event) => {
      let frame: { type: string; data?: unknown }
      try {
        frame = JSON.parse(event.data as string)
      } catch {
        return
      }
      handleFrame(frame.type, frame.data)
    }
    socket.onclose = (ev) => {
      connected.value = false
      stopHeartbeat()
      socket = null
      // 403：非运营账号；401：登录过期。这两种重连也没用，直接停掉并标记
      if (ev?.code === 1006 && !everConnected && retryCount >= MAX_RETRY) {
        disabled.value = true
      }
      if (!manualClose) scheduleReconnect()
    }
    socket.onerror = () => {
      // onclose 会跟着触发，重连逻辑统一放那里
    }
  }

  function close(): void {
    manualClose = true
    stopHeartbeat()
    if (reconnectTimer !== null) {
      window.clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    try {
      socket?.close()
    } catch {
      // 关闭失败无所谓
    }
    socket = null
    connected.value = false
  }

  onBeforeUnmount(close)

  return { connected, retries, disabled, connect, close }
}
