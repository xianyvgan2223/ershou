import { http } from '@/utils/request'

/** 购物车条目 */
export interface CartItem {
  cartId: number
  itemId: number
  title: string
  coverUrl: string
  price: number
  quantity: number
  /** 商品状态，1 在售时可结算 */
  status: number
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

/** 一键合并面交（结算）入参 */
export interface CartCheckoutParams {
  cartIds: number[]
  pointId: number
  /** 'yyyy-MM-dd HH:mm:ss' */
  timeSlotStart: string
  /** 'yyyy-MM-dd HH:mm:ss' */
  timeSlotEnd: string
  remark?: string
}

export interface CheckoutOrder {
  orderId: number
  orderNo: string
  sellerId: number
  pointName: string
}

export interface CheckoutResult {
  batchNo: string
  orders: CheckoutOrder[]
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
