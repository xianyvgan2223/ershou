/** 分页返回结构 */
export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  records: T[]
}

/** 分页请求公共参数 */
export interface PageQuery {
  pageNum?: number
  pageSize?: number
}

/** 商品分类 */
export interface Category {
  id: number
  name: string
  sort: number
}

/** 商品卡片里的卖家信息 */
export interface SellerBrief {
  id: number
  nickname: string
  avatar: string
  creditScore: number
  identityFlag: number
  shopName: string
}
