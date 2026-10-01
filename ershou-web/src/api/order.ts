import { http } from '@/utils/request'
import type { CheckoutResult } from './cart'
import type { PageQuery, PageResult } from './types'

/** 面交单角色视角 */
export type OrderRole = 'buyer' | 'seller'

/** 面交单（后端 OrderVO） */
export interface OrderVO {
  id: number
  orderNo: string
  itemId: number
  itemTitle: string
  itemCover: string
  quantity: number
  dealPrice: number
  /** 面交点，null = 待协商（私聊里定） */
  pointId: number | null
  pointName: string | null
  pointGuide: string | null
  /** 'yyyy-MM-dd HH:mm:ss'，null = 待协商 */
  timeSlotStart: string | null
  timeSlotEnd: string | null
  status: number
  /** 后端已中文化：待卖家确认 / 待面交 / 已完成 … */
  statusText: string
  buyerId: number
  buyerName: string
  sellerId: number
  sellerName: string
  remark: string
  createdAt: string
  /** 买家点过「我收到了」（只留痕，成交由卖家确认） */
  buyerConfirmed?: boolean
  sellerConfirmed?: boolean
}

export interface OrderQuery extends PageQuery {
  role: OrderRole
  /** 只看某件商品的单子（私聊窗里查"这单现在什么状态"） */
  itemId?: number
  /** 只看某个状态（私聊窗只关心进行中的 1/2） */
  status?: number
}

/** 顶栏"我的面交"红点：只算需要我动作的单子 */
export interface MeetupTodoCount {
  /** 待我确认（卖家） */
  asSeller: number
  /** 卖家已接受、我还没点"我收到了"（买家） */
  asBuyer: number
  total: number
}

export interface RejectOrderParams {
  reason: string
}

export interface ConfirmOrderResult {
  status: number
  message: string
}

/** 单品直接约面交（不经过购物车）：对应「商品详情页 → 约面交」 */
export interface CreateMeetupOrderParams {
  itemId: number
  quantity?: number
  /** 不传 = 待协商（私聊里定） */
  pointId?: number | null
  /** 'yyyy-MM-dd HH:mm:ss'，不传 = 时间待协商 */
  timeSlotStart?: string | null
  timeSlotEnd?: string | null
  remark?: string
}

export function fetchMeetupOrders(query: OrderQuery): Promise<PageResult<OrderVO>> {
  return http.get<PageResult<OrderVO>>('/meetup-orders', query)
}

export function fetchMeetupTodoCount(): Promise<MeetupTodoCount> {
  return http.get<MeetupTodoCount>('/meetup-orders/todo-count')
}

export function fetchMeetupOrder(id: number): Promise<OrderVO> {
  return http.get<OrderVO>(`/meetup-orders/${id}`)
}

/**
 * 直接约面交（单件）。
 *
 * 后端 POST /meetup-orders 一直存在，但前端从来没调过 —— 商品详情页的"约面交"
 * 只是把商品丢进购物车再跳转，用户还得自己勾选 + 找结算入口，等于约不成。
 * 返回结构与购物车合并结算一致（batchNo + orders），这里复用 CheckoutResult。
 */
export function createMeetupOrder(params: CreateMeetupOrderParams): Promise<CheckoutResult> {
  return http.post<CheckoutResult>('/meetup-orders', params)
}

/** 改面交地点（买卖双方都能改；可选同时改时间窗，都留空表示沿用原时间） */
export interface UpdateMeetupPointParams {
  pointId: number
  timeSlotStart?: string
  timeSlotEnd?: string
}

/**
 * 改面交地点：私聊页里协商好新地点后调用。
 * 后端会校验"只有待确认/待面交"能改，并给对面发一条私聊提醒。
 */
export function updateMeetupPoint(id: number, params: UpdateMeetupPointParams): Promise<OrderVO> {
  return http.patch<OrderVO>(`/meetup-orders/${id}/point`, params)
}

/** 卖家接受面交单 */
export function acceptMeetupOrder(id: number): Promise<null> {
  return http.post<null>(`/meetup-orders/${id}/accept`)
}

/** 卖家拒绝面交单 */
export function rejectMeetupOrder(id: number, reason: string): Promise<null> {
  return http.post<null>(`/meetup-orders/${id}/reject`, { reason } satisfies RejectOrderParams)
}

/** 买家取消面交单 */
export function cancelMeetupOrder(id: number, reason: string): Promise<null> {
  return http.post<null>(`/meetup-orders/${id}/cancel`, { reason } satisfies RejectOrderParams)
}

/**
 * 确认成交。
 * 卖家调用 = 交易完成（商品已售出 + 双方信用分 +2）；买家调用 = 只记录"我收到了"。
 */
export function confirmMeetupOrder(id: number): Promise<ConfirmOrderResult> {
  return http.post<ConfirmOrderResult>(`/meetup-orders/${id}/confirm`)
}
