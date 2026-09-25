import { http } from '@/utils/request'

/** 自绘地图底图配置，为 null 时前端使用 CSS 网格底图 */
export interface MapConfig {
  imgUrl?: string
  width?: number
  height?: number
}

/** 地图点位（含该点位在售商品统计） */
export interface MapPoint {
  id: number
  name: string
  /** 点位类型：1 门卫室旁 / 2 食堂 / 3 广场 / 4 快递站 / 5 教学楼大厅 / 6 校警室旁 / 7 宿舍区 / 9 其他 */
  type: number
  /** 像素坐标：coordX 对应 Leaflet 的 lng，coordY 对应 lat */
  coordX: number
  coordY: number
  safetyScore: number
  safeTags: string[]
  itemCount: number
  minPrice: number
  guideText: string
}

export interface MapPointsResult {
  mapType: number
  mapConfig: MapConfig | null
  points: MapPoint[]
}

/** 面交点（比地图点位少了商品统计） */
export interface TradePoint {
  id: number
  name: string
  type: number
  coordX: number
  coordY: number
  safetyScore: number
  safeTags: string[]
  guideText: string
}

export interface SuggestTradePointParams {
  campusId: number
  name: string
  coordX: number
  coordY: number
  guideText: string
}

/** 校内地图点位（含底图配置） */
export function fetchMapPoints(campusId = 1): Promise<MapPointsResult> {
  return http.get<MapPointsResult>('/map/points', { campusId })
}

/** 面交点列表 */
export function fetchTradePoints(campusId = 1): Promise<TradePoint[]> {
  return http.get<TradePoint[]>('/trade-points', { campusId })
}

/** 推荐新的面交点 */
export function suggestTradePoint(params: SuggestTradePointParams): Promise<null> {
  return http.post<null>('/trade-points/suggest', params)
}

/** 点位类型文案：与 sql/schema.sql 的 trade_point.point_type 字典一致 */
export function pointTypeLabel(type?: number | null): string {
  switch (type) {
    case 1:
      return '门卫室旁'
    case 2:
      return '食堂'
    case 3:
      return '广场'
    case 4:
      return '快递站'
    case 5:
      return '教学楼大厅'
    case 6:
      return '校警室旁'
    case 7:
      return '宿舍区'
    case 9:
      return '其他'
    default:
      return '校内点位'
  }
}
