import { http } from '@/utils/request'

/** 购物车条目 */
export interface CartItem {
  cartId: number
  itemId: number
  title: string
  coverUrl: string
  price: number
  quantity: number
  /** 商品状态：2=在售（可结算）/ 3=已锁定 / 4=已售出 / 5=已下架 */
  status: number
  /** 后端中文化状态文案 */
  statusText?: string
  /** 卖家设置的面交点 */
  preferPointId?: number | null
  preferPointName?: string | null
}

/** 后端已按卖家分组的购物车 */
export interface CartSellerGroup {
  sellerId: number
  sellerName: string
  items: CartItem[]
}

export interface AddCartParams {
  itemId: number
  quantity: number
}

export interface UpdateCartParams {
  quantity: number
}

/** 一键合并面交（结算）入参；面交点与时间窗都可留空 = 待协商 */
export interface CartCheckoutParams {
  cartIds: number[]
  pointId?: number | null
  /** 'yyyy-MM-dd HH:mm:ss' */
  timeSlotStart?: string | null
  /** 'yyyy-MM-dd HH:mm:ss' */
  timeSlotEnd?: string | null
  remark?: string
}

export interface CheckoutOrder {
  orderId: number
  orderNo: string
  sellerId: number
  pointName: string
}

/** 本次结算里因商品不可面交而被跳过的条目（后端只结算能约的，其余回报在这里） */
export interface CheckoutSkipped {
  cartId: number
  itemId: number
  title: string
  reason: string
}

export interface CheckoutResult {
  batchNo: string
  orders: CheckoutOrder[]
  /** 被跳过的条目：一件坏商品不再拖垮整批结算 */
  skipped?: CheckoutSkipped[]
}

export function fetchCart(): Promise<CartSellerGroup[]> {
  return http.get<CartSellerGroup[]>('/cart')
}

export function addToCart(params: AddCartParams): Promise<null> {
  return http.post<null>('/cart', params)
}

export function updateCartQuantity(cartId: number, params: UpdateCartParams): Promise<null> {
  return http.patch<null>(`/cart/${cartId}`, params)
}

export function removeCartItem(cartId: number): Promise<null> {
  return http.delete<null>(`/cart/${cartId}`)
}

export function checkoutCart(params: CartCheckoutParams): Promise<CheckoutResult> {
  return http.post<CheckoutResult>('/cart/checkout', params)
}
