import { http } from '@/utils/request'

/** 自绘地图底图配置，为 null 时前端使用 CSS 网格底图 */
export interface MapConfig {
  imgUrl?: string
  width?: number
  height?: number
  /** 校区中心点（GCJ-02 火星坐标），mapType=2 时给高德地图定位 */
  center?: { lng: number; lat: number }
  /** 高德地图默认缩放级别，校园尺度建议 15-17 */
  zoom?: number
  /** 自绘底图的像素范围 [width, height]，用于像素坐标 → 经纬度的线性换算 */
  pixelBounds?: { width: number; height: number }
  /** 每像素对应的经纬度增量（缺省时前端按 campusSize 推算） */
  lngPerPixel?: number
  latPerPixel?: number
  /** 换算时假设的校区实际边长（米），默认 800 */
  campusSizeMeters?: number
  /** 高德自定义地图样式 ID（GeoHUB），可选 */
  mapStyle?: string
}

/** 地图点位（含该点位在售商品统计） */
export interface MapPoint {
  id: number
  name: string
  /** 点位类型：1 门卫室旁 / 2 食堂 / 3 广场 / 4 快递站 / 5 教学楼大厅 / 6 校警室旁 / 7 宿舍区 / 9 其他 */
  type: number
  /** 像素坐标：coordX 对应 Leaflet 的 lng，coordY 对应 lat；仅自绘底图使用 */
  coordX?: number | null
  coordY?: number | null
  /** 真实经纬度（GCJ-02），由高德坐标拾取器采集，可为空 */
  lng?: number | null
  lat?: number | null
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
  coordX?: number | null
  coordY?: number | null
  lng?: number | null
  lat?: number | null
  safetyScore: number
  safeTags: string[]
  guideText: string
  /** 审核状态：0=待审 1=通过 2=驳回；仅 /trade-points/my-points 会返回待审点位 */
  auditStatus?: number | null
  /** 来源：1=官方 2=用户提交 3=高德搜索落库（"搜出来的地名"） */
  source?: number | null
  itemCount?: number
}

export interface SuggestTradePointParams {
  campusId: number
  name: string
  /** 自绘底图（map_type=1）的像素坐标 */
  coordX?: number
  coordY?: number
  /** 高德地图（map_type=2）的 GCJ-02 经纬度，与像素坐标二选一 */
  lng?: number
  lat?: number
  guideText?: string
  pointType?: number
}

/**
 * 校内地图点位（含底图配置）
 *
 * 注意 campusId 是**必填**的：以前写成默认值 1，结果调用方漏传时静默拿到金鸡岭校区的底图，
 * 地图中心与落库校区对不上（发布页选址踩过这个坑）。现在漏传会直接编译不过。
 */
export function fetchMapPoints(campusId: number): Promise<MapPointsResult> {
  return http.get<MapPointsResult>('/map/points', { campusId })
}

/** 面交点列表（campusId 必填，理由同 fetchMapPoints） */
export function fetchTradePoints(campusId: number): Promise<TradePoint[]> {
  return http.get<TradePoint[]>('/trade-points', { campusId })
}

/**
 * 推荐新的面交点：每次都新建一条（不做同名复用），**返回点位本身**。
 * 地图页"选点模式"用它；高德模式必须传 lng/lat，传 coordX/coordY 会把经纬度当像素存进去。
 */
export function suggestTradePoint(params: SuggestTradePointParams): Promise<TradePoint> {
  return http.post<TradePoint>('/trade-points/suggest', params)
}

/**
 * 用户在地图上新增面交点：**每次新建一条**（同名也不合并），立即返回点位（含 id）。
 * 新点位 auditStatus=1（免审核），所有人立刻可见。
 */
export function createMyTradePoint(params: SuggestTradePointParams): Promise<TradePoint> {
  return http.post<TradePoint>('/trade-points', params)
}

/** 附近点位（服务端 Redis GEO 检索，附带到我的距离） */
export interface NearbyPoint {
  id: number
  name: string
  pointType: number
  lng: number | null
  lat: number | null
  guideText: string | null
  /** 1=官方 2=用户提交 3=高德搜索落库 */
  source: number | null
  /** 距离（米），由 Redis GEOSEARCH 直接给出 */
  distanceMeters: number
}

/**
 * 我附近的点位：**服务端走 Redis GEO，不消耗任何高德配额**。
 *
 * 坐标必须是 **GCJ-02**（浏览器定位拿到的是 WGS-84，先用
 * `utils/amap.ts` 的 `browserGeolocationGcj02()` 转一下）。
 */
export function fetchNearbyPoints(params: {
  campusId: number
  lng: number
  lat: number
  radius?: number
  limit?: number
}): Promise<NearbyPoint[]> {
  return http.get<NearbyPoint[]>('/trade-points/nearby', params)
}

/**
 * 把高德搜索到的地点落库（source=3），供以后直接查库、不再消耗搜索配额。
 * 后端会先在库里按"校区+名称"找一遍，已有同名就直接返回那条（不会重复插）。
 */
export function saveSearchHitPoint(params: SuggestTradePointParams): Promise<TradePoint> {
  return http.post<TradePoint>('/trade-points/search-hit', params)
}

/** 我提交过的点位（含待审），发布页刷新后仍能选中自己新增的地点（campusId 必填） */
export function fetchMyTradePoints(campusId: number): Promise<TradePoint[]> {
  return http.get<TradePoint[]>('/trade-points/my-points', { campusId })
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
