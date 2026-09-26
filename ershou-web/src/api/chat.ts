import { http } from '@/utils/request'

/** 会话列表项（后端 ChatSessionVO） */
export interface ChatSessionVO {
  id: number
  peerId: number
  peerName: string | null
  peerAvatar: string | null
  peerCreditScore: number | null
  /** 1=商家（显示"商家"徽章） */
  peerIdentityFlag: number | null
  peerOnline: boolean
  itemId: number | null
  itemTitle: string | null
  itemCover: string | null
  itemPrice: number | null
  itemStatus: number | null
  itemStatusText: string | null
  /** 我在本会话里的角色：buyer / seller（来源商品已删除时为 null） */
  myRole: 'buyer' | 'seller' | null
  peerRole: 'buyer' | 'seller' | null
  /** 我是否拉黑了对方 / 对方是否拉黑了我 */
  blockedByMe: boolean
  blockedByPeer: boolean
  /** 当前是否允许发送（任一方拉黑则 false） */
  canSend: boolean
  lastMsgText: string | null
  lastMsgAt: string | null
  unread: number
  riskLevel: number
  createdAt: string
}

/** 消息本地状态：发送中 / 已送达（服务端 ACK 后）/ 失败 */
export type LocalStatus = 'sending' | 'sent' | 'failed'

export interface ChatMessageVO {
  id: number
  sessionId: number
  senderId: number
  receiverId: number
  /** 1=文本 2=图片 */
  msgType: number
  content: string
  riskLevel: number
  /** 命中可疑词时的弱提醒文案（不篡改原文） */
  riskWarning?: string | null
  mine: boolean
  /** 已撤回：content 为空，渲染成"你/对方撤回了一条消息" */
  recalled?: boolean
  clientMsgId?: string | null
  createdAt: string
  /** 前端本地态，不来自后端 */
  localStatus?: LocalStatus
}

export interface ChatUnread {
  total: number
  sessions: Record<string, number>
}

export interface SendMessageParams {
  sessionId: number
  content: string
  msgType?: number
  clientMsgId?: string
}

export function fetchChatSessions(): Promise<ChatSessionVO[]> {
  return http.get<ChatSessionVO[]>('/chat/sessions')
}

export function fetchChatSession(id: number): Promise<ChatSessionVO> {
  return http.get<ChatSessionVO>(`/chat/sessions/${id}`)
}

/** 从商品发起会话（已存在则复用） */
export function openChatSession(itemId: number): Promise<ChatSessionVO> {
  return http.post<ChatSessionVO>('/chat/sessions', { itemId })
}

export function fetchChatMessages(
  sessionId: number,
  params: { beforeId?: number; size?: number } = {},
): Promise<ChatMessageVO[]> {
  return http.get<ChatMessageVO[]>(`/chat/sessions/${sessionId}/messages`, params)
}

/** 发送消息（WebSocket 未连通时的兜底通道） */
export function sendChatMessage(params: SendMessageParams): Promise<ChatMessageVO> {
  return http.post<ChatMessageVO>('/chat/messages', params)
}

/** 撤回消息（仅发送方、限时 2 分钟） */
export function recallChatMessage(messageId: number): Promise<ChatMessageVO> {
  return http.post<ChatMessageVO>(`/chat/messages/${messageId}/recall`)
}

export function markChatRead(sessionId: number): Promise<null> {
  return http.post<null>(`/chat/sessions/${sessionId}/read`)
}

export function fetchChatUnread(): Promise<ChatUnread> {
  return http.get<ChatUnread>('/chat/unread')
}

export function fetchQuickReplies(): Promise<string[]> {
  return http.get<string[]>('/chat/quick-replies')
}
