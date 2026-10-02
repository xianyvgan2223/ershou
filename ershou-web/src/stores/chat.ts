import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  batchDeleteChatSessions,
  deleteChatSession,
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
  /** 最后一次收到 pong/ready 的时间，给心跳看门狗用 */
  let lastPongAt = 0

  function wsUrl(): string {
    const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    return `${proto}//${window.location.host}/ws/chat?token=${encodeURIComponent(getToken())}`
  }

  function connect(): void {
    if (!getToken()) return
    // ⚠️ 以前这里只判 `|| socket` 就直接 return：
    //   - 看门狗判定假死后把 socket 置空并 scheduleReconnect，但**没有重置 retries**，
    //     于是"连上→断→重连 5 次耗尽→之后永远不再重连"，只能刷新页面（用户就是这么撞上的）；
    //   - socket 停在 CONNECTING/CLOSING 时也会被当成"已经有连接"而跳过。
    // 现在：socket 只要不在 OPEN 态就丢掉重建，并且每次重连都重置退避计数。
    if (socket) {
      const st = socket.readyState
      if (st === WebSocket.OPEN || st === WebSocket.CONNECTING) return
      socket = null
    }
    manualClose = false
    retries = 0
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
    lastPongAt = Date.now()
    heartbeatTimer = window.setInterval(() => {
      // 整个心跳体包一层 try：setInterval 回调里抛错会让后续 tick 变得不可靠
      // （定时器不该因为一次 send/close 失败就哑掉）
      try {
        send({ type: 'ping' })
        // 看门狗：超过 2 个心跳周期没收到 pong，判定为假死 → 主动断开走重连，
        // 并把 connected 置 false 让界面提示"实时已断开"
        if (Date.now() - lastPongAt > HEARTBEAT_MS * 2 + 5_000) {
          connected.value = false
          try {
            socket?.close()
          } catch {
            /* CONNECTING 态调 close 可能抛错，忽略即可 */
          }
          socket = null
          stopHeartbeat()
          scheduleReconnect()
        }
      } catch (e) {
        console.warn('[chat] heartbeat tick failed', e)
      }
    }, HEARTBEAT_MS)
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
      // ready 也算一次"活着的证据"，把心跳看门狗一起喂上
      lastPongAt = Date.now()
      // 重连后拉一次未读，补齐断线期间的消息（不做离线队列）
      void loadUnread()
      return
    }
    if (type === 'pong') {
      // 心跳看门狗：TCP 层"连着"但实际已经僵死时，onclose 不会触发，
      // 只有靠 pong 超时才能发现（否则界面会静默地不再实时，用户只能靠刷新察觉）
      lastPongAt = Date.now()
      return
    }
    if (type === 'message') {
      const msg = normalizeMessage(data)
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
      const msg = normalizeMessage(data)
      replaceLocalMessage(msg)
      if (msg.riskWarning) showTip(msg.riskWarning, 'warning')
      return
    }
    if (type === 'recall') {
      // 对方撤回了某条消息：把本地那条替换成撤回态（后端已不下发原文）
      const recalled = normalizeMessage(data)
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

  /**
   * 消息归一化：**任何进入 store 的消息都过这一道**。
   *
   * 进来之前有三条路（历史接口 / WS 的 `message` / WS 的 `ack`），
   * 以前都是原样塞进列表 —— 只要有一帧缺字段（例如 `createdAt`），
   * 渲染时取值就会抛错。而 Vue 的渲染是"一抛全停"：
   * 整个消息列表子树不再更新，表现出来就是
   * **"按 Enter 发不出去、输入框不清空、发送按钮一直 loading"**。
   * 一条坏数据不该带走整个页面，所以在入口把字段补齐。
   */
  function normalizeMessage(raw: unknown): ChatMessageVO {
    const m = (raw ?? {}) as Partial<ChatMessageVO> & Record<string, unknown>
    const now = new Date().toISOString().slice(0, 19).replace('T', ' ')
    return {
      id: typeof m.id === 'number' ? m.id : 0,
      sessionId: typeof m.sessionId === 'number' ? m.sessionId : 0,
      senderId: typeof m.senderId === 'number' ? m.senderId : 0,
      receiverId: typeof m.receiverId === 'number' ? m.receiverId : 0,
      msgType: typeof m.msgType === 'number' ? m.msgType : 1,
      content: typeof m.content === 'string' ? m.content : '',
      riskLevel: typeof m.riskLevel === 'number' ? m.riskLevel : 0,
      mine: m.mine === true,
      recalled: m.recalled === true,
      // 时间缺失就用当前时间兜底：宁可时间不准，也不能让渲染炸掉
      createdAt: typeof m.createdAt === 'string' && m.createdAt ? m.createdAt : now,
      clientMsgId: typeof m.clientMsgId === 'string' ? m.clientMsgId : undefined,
      localStatus: typeof m.localStatus === 'string' ? m.localStatus : undefined,
      riskWarning: typeof m.riskWarning === 'string' ? m.riskWarning : undefined,
    } as ChatMessageVO
  }

  /** 正在补历史的会话（同一个会话不并发拉多次） */
  const hydrating = new Set<number>()

  /**
   * 把推送来的消息并进本地列表。
   *
   * ⚠️ 以前这里是 `if (!list) return` —— **列表还没加载就静默丢掉**。
   * 触发场景：刚打开 /chat 时自动选中最近一个会话，而它的历史还在路上
   * （`openSession` 是异步的），对方的消息正好在这几百毫秒内到达，就被扔了 ——
   * 表现就是"对方发的消息我这边要刷新才看得到"。
   * 现在：列表不存在就**先把历史拉下来**再合并，绝不静默丢帧。
   */
  function appendMessage(raw: ChatMessageVO): void {
    // 兜底再归一化一次：调用方已经归一过，但这是唯一的入口，
    // 以后新增推送类型忘了归一也不会把坏数据放进列表（列表里有坏数据 = 整页卡死）
    const msg = raw && typeof raw.createdAt === 'string' ? raw : normalizeMessage(raw)
    const list = messages.value[msg.sessionId]
    if (!list) {
      void hydrateAndAppend(msg)
      return
    }
    if (list.some((m) => m.id === msg.id)) return
    list.push(msg)
  }

  /**
   * 补历史 + 合并这一条。
   *
   * 拉回来的历史里可能**已经包含**这条（推送和入库是两步），所以按 id 去重；
   * 本地未确认的消息（id 为负、带 clientMsgId）要留在最后，别被历史冲掉。
   */
  async function hydrateAndAppend(pending: ChatMessageVO): Promise<void> {
    const sessionId = pending.sessionId
    if (hydrating.has(sessionId)) {
      // 已有一次补历史在进行：等它结束后再合并（避免并发覆盖）
      setTimeout(() => {
        const list = messages.value[sessionId]
        if (list && !list.some((m) => m.id === pending.id)) list.push(pending)
        else if (!list) void hydrateAndAppend(pending)
      }, 300)
      return
    }
    hydrating.add(sessionId)
    try {
      const history = (await fetchChatMessages(sessionId, { size: 30 })).map(normalizeMessage)
      const merged = [...history]
      if (!merged.some((m) => m.id === pending.id)) merged.push(pending)
      const locals = (messages.value[sessionId] ?? []).filter((m) => m.id < 0)
      for (const local of locals) {
        if (!merged.some((m) => m.clientMsgId && m.clientMsgId === local.clientMsgId)) {
          merged.push(local)
        }
      }
      messages.value[sessionId] = merged
    } catch {
      // 补历史失败也不能丢这一条：至少让用户看到
      const list = messages.value[sessionId] ?? []
      if (!list.some((m) => m.id === pending.id)) list.push(pending)
      messages.value[sessionId] = list
    } finally {
      hydrating.delete(sessionId)
    }
  }

  function replaceLocalMessage(raw: ChatMessageVO): void {
    const msg = raw && typeof raw.createdAt === 'string' ? raw : normalizeMessage(raw)
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
        messages.value[sessionId] = (await fetchChatMessages(sessionId, { size: 30 })).map(normalizeMessage)
      }
      await markRead(sessionId)
    } finally {
      sessionLoading.value = false
    }
  }

  /**
   * 删除聊天记录（只删自己这一侧）。
   *
   * 本地状态要一起收拾干净，否则会出现各种"删了还在"：
   * - sessions 里摘掉这一条，否则列表还显示；
   * - messages 缓存里删掉该会话的历史，否则再从商品页进来会看到旧消息一闪；
   * - currentSessionId 若正指着它要清空，否则聊天窗停在一条已删会话上；
   * - 未读角标重算，否则红点挂着点不掉。
   */
  async function deleteSession(sessionId: number): Promise<void> {
    await deleteChatSession(sessionId)
    forgetSessions([sessionId])
  }

  /**
   * 批量删除聊天记录（会话列表多选删除）。
   *
   * 本地状态一次性收拾干净（列表 / 消息缓存 / 当前会话 / 未读），
   * 与服务端"逐条独立处理"对齐：**失败的那几条要留在列表里**，
   * 否则用户会以为删掉了、刷新一下又回来。
   */
  async function deleteSessions(sessionIds: number[]): Promise<{ success: number; failures: string[] }> {
    const ids = sessionIds.filter((id) => Number.isFinite(id))
    if (!ids.length) return { success: 0, failures: [] }
    const result = await batchDeleteChatSessions(ids)
    const failedIds = new Set((result.failures ?? []).map((f) => Number(f.id)))
    const removed = ids.filter((id) => !failedIds.has(id))
    forgetSessions(removed)
    return {
      success: result.success ?? removed.length,
      failures: (result.failures ?? []).map((f) => `会话 #${f.id}：${f.reason}`),
    }
  }

  /** 从本地状态里彻底忘掉若干会话（列表 / 消息缓存 / 当前会话 / 未读） */
  function forgetSessions(ids: number[]): void {
    if (!ids.length) return
    const gone = new Set(ids)
    sessions.value = sessions.value.filter((s) => !gone.has(s.id))
    for (const id of ids) {
      delete messages.value[id]
    }
    if (currentSessionId.value !== null && gone.has(currentSessionId.value)) {
      currentSessionId.value = null
    }
    const map = { ...unreadBySession.value }
    for (const id of ids) {
      delete map[String(id)]
    }
    unreadBySession.value = map
    unreadTotal.value = sessions.value.reduce((sum, s) => sum + (s.unread ?? 0), 0)
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
    deleteSession,
    deleteSessions,
    loadMore,
    markRead,
    sendMessage,
    recall,
    refreshSession,
    loadUnread,
  }
})
