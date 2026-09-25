import { http } from '@/utils/request'
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
  pointId: number
  pointName: string
  pointGuide: string
  /** 'yyyy-MM-dd HH:mm:ss' */
  timeSlotStart: string
  timeSlotEnd: string
  status: number
  /** 后端已中文化：待卖家确认 / 待面交 / 已完成 … */
  statusText: string
  /** 仅买卖双方可见，待核销时返回 */
  verifyCode?: string
  buyerId: number
  buyerName: string
  sellerId: number
  sellerName: string
  remark: string
  createdAt: string
}

export interface OrderQuery extends PageQuery {
  role: OrderRole
}

export interface RejectOrderParams {
  reason: string
}

export interface VerifyOrderParams {
  code: string
}

export interface VerifyOrderResult {
  verifiedAt: string
  needConfirm: boolean
}

export interface ConfirmOrderResult {
  status: number
  message: string
}

export function fetchMeetupOrders(query: OrderQuery): Promise<PageResult<OrderVO>> {
  return http.get<PageResult<OrderVO>>('/meetup-orders', query)
}

export function fetchMeetupOrder(id: number): Promise<OrderVO> {
  return http.get<OrderVO>(`/meetup-orders/${id}`)
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

/** 核销码核销 */
export function verifyMeetupOrder(id: number, code: string): Promise<VerifyOrderResult> {
  return http.post<VerifyOrderResult>(`/meetup-orders/${id}/verify`, { code } satisfies VerifyOrderParams)
}

/** 确认完成 */
export function confirmMeetupOrder(id: number): Promise<ConfirmOrderResult> {
  return http.post<ConfirmOrderResult>(`/meetup-orders/${id}/confirm`)
}
