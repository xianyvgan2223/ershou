/**
 * 高德地图 JS API 2.0 加载器
 *
 * 为什么不用 npm 包：JS API 2.0 官方只提供 CDN 脚本，动态插 script 是最省依赖的方式，
 * 也便于在 key 未配置时优雅降级回 Leaflet 自绘平面图。
 *
 * 两个 key 缺一不可（2021-12-02 之后创建的 key 强制要求安全密钥）：
 *   VITE_AMAP_JS_KEY           —— 控制台「Web端(JS API)」的 key
 *   VITE_AMAP_JS_SECURITY_CODE —— 同页面的安全密钥 securityJsCode
 * 两个值都写在仓库根目录 .env，由 vite 的 envDir 读入（见 vite.config.ts）。
 */

/* ------------------------------------------------------------------ *
 * 最小化的高德类型声明（只声明本项目用到的部分，避免引入 @types 包）
 * ------------------------------------------------------------------ */
export interface AMapLngLat {
  lng: number
  lat: number
  getLng(): number
  getLat(): number
}

export interface AMapMap {
  add(overlay: unknown | unknown[]): void
  remove(overlay: unknown | unknown[]): void
  setCenter(position: [number, number]): void
  setZoom(zoom: number, immediately?: boolean): void
  setZoomAndCenter(zoom: number, center: [number, number]): void
  getZoom(): number
  getCenter(): AMapLngLat
  setFitView(overlays?: unknown[], immediately?: boolean, avoid?: number[]): void
  clearMap(): void
  resize(): void
  destroy(): void
  on(event: string, handler: (event: AMapMapEvent) => void): void
  off(event: string, handler?: (event: AMapMapEvent) => void): void
}

export interface AMapMapEvent {
  lnglat: AMapLngLat
  target: unknown
}

export interface AMapMarker {
  setMap(map: AMapMap | null): void
  setPosition(position: [number, number]): void
  getPosition(): AMapLngLat | undefined
  setContent(content: string): void
  show(): void
  hide(): void
  on(event: string, handler: (event: unknown) => void): void
  setExtData(data: unknown): void
  getExtData(): unknown
}

export interface AMapCircle {
  setMap(map: AMapMap | null): void
  setCenter(center: [number, number]): void
  setRadius(radius: number): void
}

export interface AMapGeolocation {
  getCurrentPosition(callback: (status: string, result: AMapGeolocationResult) => void): void
}

export interface AMapGeolocationResult {
  position: AMapLngLat
  accuracy: number | null
  location_type?: string
  info?: string
  message?: string
}

export interface AMapPoi {
  name?: string
  address?: string
  /** 距查询点的距离（米） */
  distance?: number
  /** POI 自身的经纬度（高德会给，别丢——它比用户随手点的位置准） */
  location?: { lng: number; lat: number }
  type?: string
  id?: string
}

export interface AMapGeocoderResult {
  regeocode?: {
    formattedAddress?: string
    pois?: AMapPoi[]
    addressComponent?: { township?: string; street?: string; streetNumber?: string }
  }
}

export interface AMapGeocoder {
  getAddress(
    lnglat: [number, number],
    callback: (status: string, result: AMapGeocoderResult) => void,
  ): void
}

/** 一个候选地点：从高德的 POI 列表里取出来的"这里可能叫什么" */
export interface GeoCandidate {
  name: string
  address: string
  /** 距点击位置的距离（米），来自高德 */
  distance: number | null
  /** 是不是 POI（false = 兜底的格式化地址"XX路XX号"） */
  isPoi: boolean
  /**
   * 这个地点自己的坐标。选了它就相当于"把点位挪到高德认定的那个位置"，
   * 比用户随手点的更准（点到楼顶/马路上都常见）。
   * 兜底的"格式化地址"没有独立坐标，为 null。
   */
  lng: number | null
  lat: number | null
}

/** 按名字搜到的地点（可以移动地图过去） */
export interface PlaceHit {
  name: string
  address: string
  lng: number
  lat: number
  distance: number | null
}

export interface AMapPlaceSearchResult {
  info?: string
  poiList?: {
    pois?: Array<{
      name?: string
      address?: string
      location?: { lng: number; lat: number }
      distance?: number
    }>
  }
}

export interface AMapPlaceSearch {
  search(
    keyword: string,
    callback: (status: string, result: AMapPlaceSearchResult) => void,
  ): void
  searchNearBy(
    keyword: string,
    center: [number, number],
    radius: number,
    callback: (status: string, result: AMapPlaceSearchResult) => void,
  ): void
  setCity(city: string): void
}

export interface AMapNamespace {
  Map: new (container: HTMLElement | string, options?: Record<string, unknown>) => AMapMap
  Marker: new (options?: Record<string, unknown>) => AMapMarker
  Circle: new (options?: Record<string, unknown>) => AMapCircle
  LngLat: new (lng: number, lat: number) => AMapLngLat
  Pixel: new (x: number, y: number) => unknown
  Size: new (width: number, height: number) => unknown
  Geolocation: new (options?: Record<string, unknown>) => AMapGeolocation
  Geocoder: new (options?: Record<string, unknown>) => AMapGeocoder
  PlaceSearch: new (options?: Record<string, unknown>) => AMapPlaceSearch
  /** 坐标系转换：把其它坐标系转成高德坐标（gcj02） */
  convertFrom(
    lnglat: [number, number] | Array<[number, number]>,
    type: 'gps' | 'baidu' | 'mapbar',
    callback: (status: string, result: { locations?: Array<{ lng: number; lat: number }> }) => void,
  ): void
  /** 插件是按需异步注入的，加载完才会挂到 AMap 命名空间上 */
  plugin(names: string | string[], callback: () => void): void
}

declare global {
  interface Window {
    AMap?: AMapNamespace
    _AMapSecurityConfig?: { securityJsCode?: string }
    AMapLoader?: {
      load(options: Record<string, unknown>): Promise<AMapNamespace>
    }
  }
}

/* ------------------------------------------------------------------ *
 * 坐标系转换已移到 ./coord.ts
 *
 * 原因：转换是纯计算，不依赖高德 SDK。放在独立文件里 Node 单测能直接 import，
 * 而本文件顶层读 import.meta.env（vite 专有），Node 里 import 不进来。
 * 这里重新导出，老的 import 路径（from '@/utils/amap'）继续可用。
 * ------------------------------------------------------------------ */
import { wgs84ToGcj02 as wgs84ToGcj02Fallback } from './coord'

export {
  browserGeolocationGcj02,
  browserRawGeolocation,
  gcj02ToWgs84,
  isOutOfChina,
  wgs84ToGcj02,
} from './coord'
export { wgs84ToGcj02 as wgs84ToGcj02Fallback } from './coord'

/**
 * WGS-84 → GCJ-02，**优先用高德自己的 `AMap.convertFrom`**。
 *
 * 为什么不用纯公式：实测（`gcj-check.html` 自检页）在同一批坐标上，
 * 公开的正算公式与高德的转换结果**恒定相差约 200 米**。高德的偏移数据是权威的，
 * 而且它随时可能调整，所以能调它就调它——公式只作为兜底（高德不可用时）。
 *
 * 这条路径**不消耗配额**：坐标系转换是 JS API 的本地能力，不打服务端接口。
 * 转换失败或 SDK 没加载时，自动退回本地公式（{@link wgs84ToGcj02Fallback}）。
 */
export async function gpsToGcj02(lng: number, lat: number): Promise<[number, number]> {
  const fallback = (): [number, number] => wgs84ToGcj02Fallback(lng, lat)
  if (!hasAmapKey()) return fallback()
  try {
    const AMap = await loadAmap()
    if (typeof AMap.convertFrom !== 'function') return fallback()
    const converted = await new Promise<[number, number] | null>((resolve) => {
      AMap.convertFrom([lng, lat], 'gps', (status, result) => {
        const first = result?.locations?.[0]
        if (status === 'complete' && first) resolve([first.lng, first.lat])
        else resolve(null)
      })
    })
    return converted ?? fallback()
  } catch {
    return fallback()
  }
}

/* ------------------------------------------------------------------ *
 * 加载逻辑
 * ------------------------------------------------------------------ */
const JS_API_VERSION = '2.0'
const SCRIPT_ID = 'amap-js-api'

/** 地图页需要的能力，按需加载，避免拖慢首屏 */
export const AMAP_PLUGINS = ['AMap.Scale', 'AMap.ToolBar', 'AMap.Geolocation']
export const AMAP_JS_KEY = (import.meta.env.VITE_AMAP_JS_KEY ?? '').trim()
export const AMAP_SECURITY_CODE = (import.meta.env.VITE_AMAP_JS_SECURITY_CODE ?? '').trim()

/** key 是否已配置：未配置时地图页自动退回自绘平面图 */
export function hasAmapKey(): boolean {
  return AMAP_JS_KEY.length > 0 && AMAP_SECURITY_CODE.length > 0
}

/** 加载结果缓存，多个组件重复调用只加载一次 */
let loading: Promise<AMapNamespace> | null = null

function injectScript(): Promise<void> {
  if (window.AMapLoader) return Promise.resolve()
  const existing = document.getElementById(SCRIPT_ID) as HTMLScriptElement | null
  if (existing) {
    return new Promise((resolve, reject) => {
      existing.addEventListener('load', () => resolve())
      existing.addEventListener('error', () => reject(new Error('高德 JS API 脚本加载失败')))
    })
  }
  return new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.id = SCRIPT_ID
    script.async = true
    script.src = `https://webapi.amap.com/loader.js`
    script.onload = () => resolve()
    script.onerror = () =>
      reject(new Error('无法访问 webapi.amap.com，请检查网络或代理设置'))
    document.head.appendChild(script)
  })
}

/**
 * 加载高德 JS API。
 * 必须先把安全密钥挂到 window._AMapSecurityConfig，否则会报 INVALID_USER_SCODE。
 */
export function loadAmap(): Promise<AMapNamespace> {
  if (window.AMap) return Promise.resolve(window.AMap)
  if (loading) return loading

  if (!hasAmapKey()) {
    return Promise.reject(
      new Error('未配置高德 Key：请在仓库根目录 .env 里填写 VITE_AMAP_JS_KEY 与 VITE_AMAP_JS_SECURITY_CODE'),
    )
  }

  window._AMapSecurityConfig = { securityJsCode: AMAP_SECURITY_CODE }

  loading = injectScript()
    .then(() => {
      const loader = window.AMapLoader
      if (!loader) throw new Error('高德 loader.js 未正确加载')
      return loader.load({
        key: AMAP_JS_KEY,
        version: JS_API_VERSION,
        plugins: AMAP_PLUGINS,
      })
    })
    .then((AMap) => {
      window.AMap = AMap
      return AMap
    })
    .catch((error: unknown) => {
      loading = null
      const raw = error instanceof Error ? error.message : String(error)
      // 常见错误：INVALID_USER_KEY（key 类型选错）/ INVALID_USER_SCODE（安全密钥不对）
      if (/INVALID_USER_KEY/i.test(raw)) {
        throw new Error('高德 Key 无效：请确认该 key 的服务平台选的是「Web端(JS API)」')
      }
      if (/INVALID_USER_SCODE/i.test(raw)) {
        throw new Error('高德安全密钥无效：请核对 .env 里的 VITE_AMAP_JS_SECURITY_CODE')
      }
      throw new Error(raw)
    })

  return loading
}

/** 单独加载一个插件（不在 AMAP_PLUGINS 预加载列表里的，用这个按需补） */
export function loadAmapPlugin<T = unknown>(name: string): Promise<T> {
  return loadAmap().then(
    (AMap) =>
      new Promise<T>((resolve, reject) => {
        AMap.plugin(name, () => {
          const ctor = (AMap as unknown as Record<string, unknown>)[name.replace(/^AMap\./, '')]
          if (!ctor) {
            reject(new Error(`高德插件 ${name} 未就绪`))
            return
          }
          resolve(ctor as T)
        })
      }),
  )
}

/**
 * 唤起高德地图 App / 网页版做步行导航。
 *
 * 为什么用 URI 而不是 JS API 的步行路径规划插件：URI 是纯链接跳转，
 * **不消耗任何接口配额**，也不用在地图里再叠一层路线图层。
 * 传 GCJ-02 坐标（高德自己的坐标系），所以不需要任何转换。
 *
 * 参考：https://developer.amap.com/api/uri-api/guide/travel/route
 */
export function amapWalkingUri(
  lng: number,
  lat: number,
  options: { name?: string; fromMyLocation?: boolean } = {},
): string {
  const name = options.name ? encodeURIComponent(options.name) : '面交点'
  // 起点用"我的位置"：高德 App 会自己定位，省掉我们再取一次坐标
  const from = options.fromMyLocation === false ? '' : '&sname=我的位置'
  return `https://uri.amap.com/navigation?to=${lng},${lat},${name}&mode=walk&policy=1&src=ershou&coordinate=gaode${from}`
}

/** 在网页版高德地图上查看某个坐标（没有 App 时的兜底） */
export function amapWebUri(lng: number, lat: number, name?: string): string {
  const label = name ? encodeURIComponent(name) : '面交点'
  return `https://uri.amap.com/marker?position=${lng},${lat}&name=${label}&src=ershou&coordinate=gaode&callnative=0`
}

/** 地点搜索（按名字）插件：注意配额只有 5000 次/月，**只在用户明确点"搜索"时调用** */
export const AMAP_PLACE_SEARCH_PLUGIN = 'AMap.PlaceSearch'

/**
 * 按名字在高德上搜地点，返回可导航的坐标。
 *
 * 用途：用户想填"相思湖""清苑11栋"这类名字时，先看看高德上到底有没有、在哪；
 * 搜到就顺手把地图挪过去（坐标比随手点一下准），搜不到就尊重用户的自由填写。
 *
 * ⚠️ 配额红线：个人认证的「基础搜索服务」只有 **5000 次/月**（地图初始化是 150 万次），
 * 所以这里只在用户点按钮时调，绝不做输入即搜。
 */
export function searchPlaces(
  keyword: string,
  options: { near?: [number, number]; radius?: number; pageSize?: number } = {},
): Promise<PlaceHit[]> {
  const trimmed = keyword.trim()
  if (!trimmed) return Promise.resolve([])
  return loadAmapPlugin<new (options?: Record<string, unknown>) => AMapPlaceSearch>(
    AMAP_PLACE_SEARCH_PLUGIN,
  )
    .then(
      (PlaceSearch) =>
        new Promise<PlaceHit[]>((resolve) => {
          const search = new PlaceSearch({ pageSize: options.pageSize ?? 6, pageIndex: 1 })
          const collect = (status: string, result: AMapPlaceSearchResult) => {
            if (status !== 'complete') {
              resolve([])
              return
            }
            const pois = result.poiList?.pois ?? []
            const hits: PlaceHit[] = []
            const seen = new Set<string>()
            pois.forEach((poi) => {
              const name = (poi.name ?? '').trim()
              const location = poi.location
              if (!name || !location || seen.has(name)) return
              seen.add(name)
              hits.push({
                name,
                address: poi.address ?? '',
                lng: location.lng,
                lat: location.lat,
                distance: typeof poi.distance === 'number' ? poi.distance : null,
              })
            })
            resolve(hits)
          }
          try {
            // 有参考坐标就搜周边（结果自带距离，能判断"是不是我点的这附近"）；
            // 没有就给全市关键字搜索
            if (options.near) {
              search.searchNearBy(trimmed, options.near, options.radius ?? 3000, collect)
            } else {
              search.setCity('全国')
              search.search(trimmed, collect)
            }
          } catch {
            resolve([])
          }
        }),
    )
    .catch(() => [])
}

/**
 * 逆地理编码（单值）：把坐标翻译成"花江校区图书馆"这样的地点名，取最近的一个。
 * 需要"让用户挑"的场景请用 {@link reverseGeocodeCandidates}。
 */
export function reverseGeocode(
  lng: number,
  lat: number,
): Promise<{ name: string; address: string } | null> {
  return reverseGeocodeCandidates(lng, lat).then((list) => {
    const first = list.find((item) => item.isPoi) ?? list[0]
    return first ? { name: first.name, address: first.address } : null
  })
}

/**
 * 逆地理编码（多候选）：返回点击位置附近**所有可用的地名**，按距离从近到远。
 *
 * 为什么给多个而不是一个：只取最近的那个经常不是用户想要的名字——
 * 站在图书馆门口点地图，最近的可能是一辆共享单车或某个小店。
 * 所以上层（`PointPickerDialog`）会把候选列出来让用户挑。
 *
 * 只认 GCJ-02（高德坐标系）。浏览器 GPS 拿到的 WGS-84 在国内有 50~200 米偏移，
 * 识别出的地点可能差一栋楼——调用方需要自己保证传入的是 GCJ-02。
 *
 * 备注：走 JS API 的 `AMap.Geocoder`（地图页本来就加载了这个插件），
 * 不额外调用 Web 服务接口，也不占用 Web 服务 key 的 IP 白名单。
 */
export function reverseGeocodeCandidates(
  lng: number,
  lat: number,
  options: { radius?: number } = {},
): Promise<GeoCandidate[]> {
  return loadAmapPlugin<new (options?: Record<string, unknown>) => AMapGeocoder>('AMap.Geocoder')
    .then(
      (Geocoder) =>
        new Promise<GeoCandidate[]>((resolve) => {
          // radius 500 + extensions all：一次拿到周边一圈 POI，校园尺度下足够
          const geocoder = new Geocoder({ radius: options.radius ?? 500, extensions: 'all' })
          geocoder.getAddress([lng, lat], (status, result) => {
            const regeo = result?.regeocode
            if (status !== 'complete' || !regeo) {
              resolve([])
              return
            }
            const pois = (regeo.pois ?? []).filter((poi) => Boolean(poi.name))
            // 高德返回的顺序不完全等于距离顺序，这里自己排一遍
            const sorted = [...pois].sort(
              (a, b) => (a.distance ?? Number.MAX_SAFE_INTEGER) - (b.distance ?? Number.MAX_SAFE_INTEGER),
            )
            const candidates: GeoCandidate[] = []
            const seen = new Set<string>()
            sorted.forEach((poi) => {
              const name = (poi.name ?? '').trim()
              if (!name || seen.has(name)) return
              seen.add(name)
              candidates.push({
                name,
                address: poi.address ?? regeo.formattedAddress ?? '',
                distance: typeof poi.distance === 'number' ? poi.distance : null,
                isPoi: true,
                // 高德给的 POI 精确坐标：选了它就把点位挪过去
                lng: poi.location?.lng ?? null,
                lat: poi.location?.lat ?? null,
              })
            })
            // 兜底：把格式化地址也作为一个候选，POI 都不合适时还能选门牌号
            const formatted = (regeo.formattedAddress ?? '').trim()
            if (formatted && !seen.has(formatted)) {
              candidates.push({
                name: formatted,
                address: '',
                distance: null,
                isPoi: false,
                // 门牌地址没有独立坐标，保持用户点击的位置
                lng: null,
                lat: null,
              })
            }
            resolve(candidates.slice(0, 8))
          })
        }),
    )
    .catch(() => [])
}
