import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  fetchChatMessages,
  fetchChatSessions,
  fetchChatUnread,
  markChatRead,
  recallChatMessage,
  sendChatMessage,
} from '@/api/chat'
import type { ChatMessageVO, ChatSessionVO } from '@/api/chat'
import { getToken } from '@/utils/request'
import { showTip } from '@/utils/feedback'

/** 断线重连：指数退避，最多 5 次（开发文档 7.6） */
const MAX_RETRY = 5
const HEARTBEAT_MS = 30_000

/**
 * 私聊状态机：会话/消息 + WebSocket 生命周期。
 *
 * 发送策略：连接可用走 WS（落库成功才回 ACK）；不可用回落 REST，保证"网断了也能发"。
 * 本地消息先以 sending 入列，收到 ack 替换为服务端消息，收到 error 标 failed。
 */
export const useChatStore = defineStore('chat', () => {
  const sessions = ref<ChatSessionVO[]>([])
  const messages = ref<Record<number, ChatMessageVO[]>>({})
  const unreadTotal = ref(0)
  const unreadBySession = ref<Record<string, number>>({})
  const currentSessionId = ref<number | null>(null)
  const connected = ref(false)
  /** 对方已读的会话 id（用于给自己最后一条消息显示"已读"） */
  const peerReadSessionId = ref<number | null>(null)
  const loadingSessions = ref(false)
  const quickReplies = ref<string[]>([])
  const sessionLoading = ref(false)

  const currentSession = computed(
    () => sessions.value.find((s) => s.id === currentSessionId.value) ?? null,
  )

  let socket: WebSocket | null = null
  let heartbeatTimer: number | null = null
  let reconnectTimer: number | null = null
  let retries = 0
  let manualClose = false

  function wsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${proto}//${window.location.host}/ws/chat?token=${encodeURIComponent(getToken())}`
  }

  function connect(): void {
    if (!getToken() || socket) return
    manualClose = false
    try {
      socket = new WebSocket(wsUrl())
    } catch {
      scheduleReconnect()
      return
    }
    socket.onopen = () => {
      retries = 0
      startHeartbeat()
    }
    socket.onmessage = (event) => {
      let frame: { type: string; data?: unknown }
      try {
        frame = JSON.parse(event.data)
      } catch {
        return
      }
      handleFrame(frame.type, frame.data)
    }
    socket.onclose = () => {
      connected.value = false
      stopHeartbeat()
      socket = null
      if (!manualClose) scheduleReconnect()
    }
    socket.onerror = () => {
      // onclose 会跟着触发，重连逻辑统一放那里
    }
  }

  function scheduleReconnect(): void {
    if (manualClose || retries >= MAX_RETRY) return
    const delay = Math.min(1000 * 2 ** retries, 16_000)
    retries += 1
    reconnectTimer = window.setTimeout(() => connect(), delay)
  }

  function startHeartbeat(): void {
    stopHeartbeat()
    heartbeatTimer = window.setInterval(() => send({ type: 'ping' }), HEARTBEAT_MS)
  }

  function stopHeartbeat(): void {
    if (heartbeatTimer !== null) {
      window.clearInterval(heartbeatTimer)
      heartbeatTimer = null
    }
  }

  function disconnect(): void {
    manualClose = true
    stopHeartbeat()
    if (reconnectTimer !== null) {
      window.clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    socket?.close()
    socket = null
    connected.value = false
  }

  function send(payload: Record<string, unknown>): boolean {
    if (socket && socket.readyState === WebSocket.OPEN) {
      socket.send(JSON.stringify(payload))
      return true
    }
    return false
  }

  function handleFrame(type: string, data: unknown): void {
    if (type === 'ready') {
      connected.value = true
      // 重连后拉一次未读，补齐断线期间的消息（不做离线队列）
      void loadUnread()
      return
    }
    if (type === 'message') {
      const msg = data as ChatMessageVO
      appendMessage(msg)
      const session = sessions.value.find((s) => s.id === msg.sessionId)
      if (session) {
        session.lastMsgText = msg.msgType === 2 ? '[图片]' : msg.content
        session.lastMsgAt = msg.createdAt
        if (currentSessionId.value === msg.sessionId && document.hasFocus()) {
          void markRead(msg.sessionId)
        } else {
          session.unread = (session.unread ?? 0) + 1
          unreadTotal.value += 1
        }
      } else {
        void loadSessions()
      }
      return
    }
    if (type === 'ack') {
      const msg = data as ChatMessageVO
      replaceLocalMessage(msg)
      if (msg.riskWarning) showTip(msg.riskWarning, 'warning')
      return
    }
    if (type === 'recall') {
      // 对方撤回了某条消息：把本地那条替换成撤回态（后端已不下发原文）
      const recalled = data as ChatMessageVO
      const list = messages.value[recalled.sessionId]
      if (list) {
        const index = list.findIndex((m) => m.id === recalled.id)
        if (index >= 0) list.splice(index, 1, recalled)
      }
      const session = sessions.value.find((s) => s.id === recalled.sessionId)
      const last = list?.[list.length - 1]
      if (session && last && last.id === recalled.id) session.lastMsgText = '[已撤回]'
      return
    }
    if (type === 'read') {
      const payload = data as { sessionId: number }
      peerReadSessionId.value = payload.sessionId
      return
    }
    if (type === 'error') {
      const payload = data as { clientMsgId?: string; message?: string }
      if (payload.clientMsgId) markLocalFailed(payload.clientMsgId)
      showTip(payload.message || '消息发送失败', 'error')
    }
  }

  function appendMessage(msg: ChatMessageVO): void {
    const list = messages.value[msg.sessionId]
    if (!list) return
    if (list.some((m) => m.id === msg.id)) return
    list.push(msg)
  }

  function replaceLocalMessage(msg: ChatMessageVO): void {
    const list = messages.value[msg.sessionId]
    if (!list) return
    const index = list.findIndex((m) => m.clientMsgId && m.clientMsgId === msg.clientMsgId)
    if (index >= 0) list.splice(index, 1, msg)
    else if (!list.some((m) => m.id === msg.id)) list.push(msg)
  }

  function markLocalFailed(clientMsgId: string): void {
    for (const list of Object.values(messages.value)) {
      const target = list.find((m) => m.clientMsgId === clientMsgId)
      if (target) {
        target.localStatus = 'failed'
        return
      }
    }
  }

  async function loadSessions(): Promise<void> {
    loadingSessions.value = true
    try {
      const list = await fetchChatSessions()
      sessions.value = list
      const map: Record<string, number> = {}
      list.forEach((s) => {
        map[String(s.id)] = s.unread ?? 0
      })
      unreadBySession.value = map
      unreadTotal.value = list.reduce((sum, s) => sum + (s.unread ?? 0), 0)
    } finally {
      loadingSessions.value = false
    }
  }

  /** 打开某会话：加载历史并清零未读 */
  async function openSession(sessionId: number): Promise<void> {
    currentSessionId.value = sessionId
    sessionLoading.value = true
    try {
      if (!messages.value[sessionId]) {
        messages.value[sessionId] = await fetchChatMessages(sessionId, { size: 30 })
      }
      await markRead(sessionId)
    } finally {
      sessionLoading.value = false
    }
  }

  /** 往前翻历史（id 游标） */
  async function loadMore(sessionId: number): Promise<number> {
    const list = messages.value[sessionId] ?? []
    if (!list.length) return 0
    const older = await fetchChatMessages(sessionId, { beforeId: list[0].id, size: 30 })
    if (older.length) messages.value[sessionId] = [...older, ...list]
    return older.length
  }

  async function markRead(sessionId: number): Promise<void> {
    try {
      await markChatRead(sessionId)
    } catch {
      return
    }
    const session = sessions.value.find((s) => s.id === sessionId)
    const previous = session?.unread ?? 0
    if (session) session.unread = 0
    unreadBySession.value = { ...unreadBySession.value, [String(sessionId)]: 0 }
    unreadTotal.value = Math.max(0, unreadTotal.value - previous)
  }

  /** 发消息：WS 优先，不可用走 REST */
  async function sendMessage(sessionId: number, content: string, msgType = 1): Promise<void> {
    const text = content.trim()
    if (!text) return
    const clientMsgId = `c-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
    const local: ChatMessageVO = {
      id: -Date.now(),
      sessionId,
      senderId: 0,
      receiverId: 0,
      msgType,
      content: text,
      riskLevel: 0,
      mine: true,
      clientMsgId,
      createdAt: new Date().toISOString().slice(0, 19).replace('T', ' '),
      localStatus: 'sending',
    }
    if (!messages.value[sessionId]) messages.value[sessionId] = []
    messages.value[sessionId].push(local)

    if (send({ type: 'send', sessionId, msgType, content: text, clientMsgId })) {
      return
    }
    try {
      const saved = await sendChatMessage({ sessionId, content: text, msgType, clientMsgId })
      replaceLocalMessage(saved)
      if (saved.riskWarning) showTip(saved.riskWarning, 'warning')
    } catch {
      markLocalFailed(clientMsgId)
    }
  }

  /** 撤回自己发的消息（后端限时 2 分钟，超时会返回 70007） */
  async function recall(messageId: number): Promise<void> {
    const recalled = await recallChatMessage(messageId)
    const list = messages.value[recalled.sessionId]
    if (list) {
      const index = list.findIndex((m) => m.id === messageId)
      if (index >= 0) list.splice(index, 1, recalled)
    }
    const session = sessions.value.find((s) => s.id === recalled.sessionId)
    const last = list?.[list.length - 1]
    if (session && last && last.id === messageId) session.lastMsgText = '[已撤回]'
  }

  /** 拉黑/解除拉黑后刷新会话（canSend 会跟着变） */
  async function refreshSession(sessionId: number): Promise<void> {
    const { fetchChatSession } = await import('@/api/chat')
    const detail = await fetchChatSession(sessionId)
    const index = sessions.value.findIndex((s) => s.id === sessionId)
    if (index >= 0) sessions.value.splice(index, 1, detail)
    else sessions.value.unshift(detail)
  }

  async function loadUnread(): Promise<void> {
    try {
      const data = await fetchChatUnread()
      unreadTotal.value = data.total ?? 0
      unreadBySession.value = data.sessions ?? {}
      sessions.value.forEach((s) => {
        s.unread = data.sessions?.[String(s.id)] ?? s.unread
      })
    } catch {
      // 顶栏红点失败不影响聊天本身
    }
  }

  return {
    sessions,
    messages,
    unreadTotal,
    unreadBySession,
    currentSessionId,
    currentSession,
    connected,
    peerReadSessionId,
    loadingSessions,
    sessionLoading,
    quickReplies,
    connect,
    disconnect,
    loadSessions,
    openSession,
    loadMore,
    markRead,
    sendMessage,
    recall,
    refreshSession,
    loadUnread,
  }
})
