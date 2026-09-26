import { http } from '@/utils/request'
import type { PageQuery, PageResult, SellerBrief } from './types'
import type { TradePoint } from './map'

/** 排序：最新 / 价格升序 / 价格降序 */
export type ItemSort = 'latest' | 'price_asc' | 'price_desc'

/** 商品列表 / 详情返回结构（后端 ItemVO） */
export interface ItemVO {
  id: number
  title: string
  price: number
  originalPrice: number | null
  /** 0=一口价，1=可议价（后端 Integer，非布尔） */
  negotiable: 0 | 1
  conditionLevel: number
  coverUrl: string
  images: string[]
  categoryId: number
  categoryName: string
  campusId: number
  preferPointId: number | null
  preferPointName: string | null
  /** 服务端返回的状态中文（在售 / 已售出 …） */
  statusText?: string
  /** 详情接口返回的面交点指引文案 */
  preferPointGuide?: string | null
  status: number
  viewCount: number
  favCount: number
  /** 当前登录用户是否已收藏（未登录恒为 false；列表和详情接口都会返回） */
  favorited?: boolean
  publishedAt: string
  seller: SellerBrief
  /** 仅详情接口返回 */
  description?: string
  /** 搜索命中时的标题高亮片段（只含 <em> 标签，ES 已做 HTML 转义） */
  highlightTitle?: string | null
  /** 搜索命中时的描述高亮片段 */
  highlightDescription?: string | null
  /** 仅详情接口返回 */
  preferPoint?: TradePoint | null
}

/** GET /items 查询参数 */
export interface ItemQuery extends PageQuery {
  keyword?: string
  categoryId?: number
  sort?: ItemSort
}

/** POST /items 发布入参 */
export interface PublishItemParams {
  title: string
  description: string
  categoryId: number
  conditionLevel: number
  price: number
  originalPrice?: number | null
  /** 0=一口价，1=可议价（后端 Integer，非布尔） */
  negotiable: 0 | 1
  stock: number
  /** 0=多件库存，1=孤品（后端 Integer，非布尔） */
  isUnique: 0 | 1
  images: string[]
  preferPointId?: number | null
}

/** POST /items 返回 */
export interface PublishItemResult {
  itemId: number
  status: number
  statusText: string
}

/** 商品列表 / 搜索 */
export function fetchItems(query: ItemQuery): Promise<PageResult<ItemVO>> {
  return http.get<PageResult<ItemVO>>('/items', query)
}

/** 商品详情 */
export function fetchItemDetail(id: number | string): Promise<ItemVO> {
  return http.get<ItemVO>(`/items/${id}`)
}

/** 发布商品（违规时后端返回非 0 code，例如 50001，由响应拦截统一提示） */
export function publishItem(params: PublishItemParams): Promise<PublishItemResult> {
  return http.post<PublishItemResult>('/items', params)
}

/** 下架商品 */
export function offlineItem(id: number): Promise<null> {
  return http.post<null>(`/items/${id}/offline`)
}

/** 我的发布 */
export function fetchMyItems(query: PageQuery): Promise<PageResult<ItemVO>> {
  return http.get<PageResult<ItemVO>>('/items/mine', query)
}

/** 我的收藏 */
export function fetchFavorites(query: PageQuery): Promise<PageResult<ItemVO>> {
  return http.get<PageResult<ItemVO>>('/favorites', query)
}

/** 收藏 */
export function addFavorite(itemId: number): Promise<null> {
  return http.post<null>(`/favorites/${itemId}`)
}

/** 取消收藏 */
export function removeFavorite(itemId: number): Promise<null> {
  return http.delete<null>(`/favorites/${itemId}`)
}
