/**
 * 坐标系工具（**不依赖高德 SDK**，纯计算）。
 *
 * 本项目涉及三套坐标：
 * - **GCJ-02**：国测局坐标，高德地图用，也是本项目 `trade_point.lng/lat` 入库的坐标；
 * - **WGS-84**：真实 GPS，浏览器 `navigator.geolocation` 给的就是这个；
 * - **像素坐标**：`map_type=1` 的自绘底图校区用（`coord_x/coord_y`），不是地理坐标。
 *
 * 国内 GCJ-02 与 WGS-84 相差 **50~200 米**，校园尺度下就是一栋楼。
 * 所以"用户定位 → 附近点位检索"这条路上**必须先转换**，否则会选错楼。
 *
 * 放在独立文件里（而不是塞在 `utils/amap.ts`）是为了能在 Node 里直接跑单测：
 * `amap.ts` 顶层读 `import.meta.env`，那是 vite 专有的，Node 里 import 不进来。
 */

/** 中国大陆粗略范围：这个范围外不套用 GCJ-02 偏移 */
const CHINA_LNG_MIN = 72.004
const CHINA_LNG_MAX = 137.8347
const CHINA_LAT_MIN = 0.8293
const CHINA_LAT_MAX = 55.8271

const ELLIPSOID_A = 6378245.0 // 克拉索夫斯基椭球长半轴（米）
const ELLIPSOID_EE = 0.00669342162296594323 // 第一偏心率平方

export function isOutOfChina(lng: number, lat: number): boolean {
  return lng < CHINA_LNG_MIN || lng > CHINA_LNG_MAX || lat < CHINA_LAT_MIN || lat > CHINA_LAT_MAX
}

function transformLat(x: number, y: number): number {
  // 注意首项是 **+100.0**：写成 -100.0 会让偏移量翻倍到 500 多米、方向还反了
  // （这个符号错误实测过：桂林地区会偏出 566 米，正常应是 150 米左右）
  let ret = 100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
  ret += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0
  ret += ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0
  return ret
}

function transformLng(x: number, y: number): number {
  let ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
  ret += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0
  ret += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0
  ret += ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0
  return ret
}

/**
 * WGS-84（浏览器 GPS）→ GCJ-02（高德 / 本项目入库坐标）。
 *
 * 公开的偏移正算公式，纯本地计算，**不调用任何接口、不消耗配额**。
 * 境外坐标原样返回（国外没有这个偏移）。
 */
export function wgs84ToGcj02(lng: number, lat: number): [number, number] {
  if (isOutOfChina(lng, lat)) return [lng, lat]

  let dLat = transformLat(lng - 105.0, lat - 35.0)
  let dLng = transformLng(lng - 105.0, lat - 35.0)
  const radLat = (lat / 180.0) * Math.PI
  let magic = Math.sin(radLat)
  magic = 1 - ELLIPSOID_EE * magic * magic
  const sqrtMagic = Math.sqrt(magic)
  dLat = (dLat * 180.0) / (((ELLIPSOID_A * (1 - ELLIPSOID_EE)) / (magic * sqrtMagic)) * Math.PI)
  dLng = (dLng * 180.0) / ((ELLIPSOID_A / sqrtMagic) * Math.cos(radLat) * Math.PI)
  return [lng + dLng, lat + dLat]
}

/**
 * GCJ-02 → WGS-84（反算，用数值迭代逼近）。
 *
 * 用于"把库里的点位坐标换算成真实 GPS 坐标"这类反向场景
 * （例如以后要把点位喂给只认 WGS-84 的第三方服务）。
 */
export function gcj02ToWgs84(lng: number, lat: number): [number, number] {
  if (isOutOfChina(lng, lat)) return [lng, lat]
  let wLng = lng
  let wLat = lat
  // 迭代 3 次足够收敛到亚米级
  for (let i = 0; i < 3; i++) {
    const [gLng, gLat] = wgs84ToGcj02(wLng, wLat)
    wLng += lng - gLng
    wLat += lat - gLat
  }
  return [wLng, wLat]
}

/**
 * 用浏览器原生定位拿**原始 WGS-84**（**不消耗高德配额**）。
 * 失败（无权限/超时/不支持）返回 null。
 *
 * 需要 GCJ-02 时先用这个拿原始坐标，再用 `gpsToGcj02`（`utils/amap.ts`，优先走高德转换），
 * 不要直接用一个"定位+转换"的封装——那样就没机会用高德的权威转换了。
 */
export function browserRawGeolocation(): Promise<[number, number] | null> {
  return new Promise((resolve) => {
    if (typeof navigator === 'undefined' || !navigator.geolocation) {
      resolve(null)
      return
    }
    navigator.geolocation.getCurrentPosition(
      (pos) => resolve([pos.coords.longitude, pos.coords.latitude]),
      () => resolve(null),
      { enableHighAccuracy: true, timeout: 8000, maximumAge: 60_000 },
    )
  })
}

/**
 * 用浏览器原生定位拿当前位置（**不消耗高德配额**），并用**本地公式**转成 GCJ-02。
 *
 * ⚠️ 这个公式与高德的权威结果实测有约 200 米差异（见 `gcj-check.html`）。
 * 生产路径请用 `browserRawGeolocation()` + `gpsToGcj02()`（后者优先走高德转换），
 * 本函数只作兜底与离线场景使用。
 */
export function browserGeolocationGcj02(): Promise<[number, number] | null> {
  return browserRawGeolocation().then((raw) => (raw ? wgs84ToGcj02(raw[0], raw[1]) : null))
}
