/**
 * 面交点合并（仅用于展示/选择，不落库）。
 *
 * 为什么要合并：用户在地图上随手新增的点位常常挨得很近（同一栋楼、同一个门口），
 * 点位一多地图上就挤成一团、下拉里也全是重复项。
 *
 * 合并规则：**距离不足 100 米**视为同一个地方，归为一组。
 *   - 地图上只画一个气泡，商品数是组内求和；
 *   - 下拉里只出现一条，名称带「（N 个点位）」；
 *   - 但**商品详情 / 面交单永远显示精确点位**（pointId 是真实的一条），
 *     合并只影响"看和选"，不影响任何引用关系——这点很关键，否则会串数据。
 */

/** 合并阈值（米）：不足 100 米算同一个地方 */
export const MERGE_RADIUS_METERS = 100

/** 参与合并的最小字段（MapPoint / TradePoint 都满足） */
export interface GeoPointLike {
  id: number
  name: string
  lng?: number | null
  lat?: number | null
  itemCount?: number
  minPrice?: number
}

export interface PointGroup<T> {
  /** 代表点位：用组内第一个点（坐标就是它的） */
  rep: T
  /** 组内全部点位（含 rep），长度 >= 1 */
  members: T[]
}

/** 两点球面距离（米） */
export function geoDistanceMeters(a: [number, number], b: [number, number]): number {
  const R = 6371008.8
  const rad = (d: number) => (d * Math.PI) / 180
  const dLat = rad(b[1] - a[1])
  const dLng = rad(b[0] - a[0])
  const h =
    Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(h)))
}

function lngLatOf(point: GeoPointLike): [number, number] | null {
  if (!point.lng || !point.lat) return null
  return [point.lng, point.lat]
}

/**
 * 按距离把点位分组（贪心）。
 *
 * 没有经纬度的点位（纯像素坐标的自绘底图校区）**各自单独成组**，
 * 不能拿像素当米用——那会把不相干的点位错误合并。
 */
export function groupPointsByDistance<T extends GeoPointLike>(
  points: T[],
  radiusMeters = MERGE_RADIUS_METERS,
): PointGroup<T>[] {
  const groups: PointGroup<T>[] = []
  const withGeo: Array<{ point: T; lngLat: [number, number] }> = []
  const withoutGeo: T[] = []

  points.forEach((point) => {
    const lngLat = lngLatOf(point)
    if (lngLat) withGeo.push({ point, lngLat })
    else withoutGeo.push(point)
  })

  withGeo.forEach(({ point, lngLat }) => {
    const hit = groups.find((group) => {
      const repLngLat = lngLatOf(group.rep)
      return Boolean(repLngLat && geoDistanceMeters(repLngLat, lngLat) < radiusMeters)
    })
    if (hit) {
      hit.members.push(point)
    } else {
      groups.push({ rep: point, members: [point] })
    }
  })

  withoutGeo.forEach((point) => groups.push({ rep: point, members: [point] }))
  return groups
}

/** 组内商品数求和 */
export function groupItemCount<T extends GeoPointLike>(group: PointGroup<T>): number {
  return group.members.reduce((sum, point) => sum + (point.itemCount ?? 0), 0)
}

/** 组内最低价（没有价格的返回 null） */
export function groupMinPrice<T extends GeoPointLike>(group: PointGroup<T>): number | null {
  const prices = group.members
    .map((point) => point.minPrice)
    .filter((price): price is number => typeof price === 'number' && price > 0)
  return prices.length ? Math.min(...prices) : null
}

/**
 * 把一组点位合成一个"用于展示的点位"：沿用代表点位的 id 与坐标，
 * 名称加「（N 个点位）」后缀，商品数/最低价取组内汇总。
 *
 * 注意：这个合成对象的 id 只是代表点位的 id，**不要拿它当"这一组的 id"去写库**。
 */
export function mergedPointOf<T extends GeoPointLike>(group: PointGroup<T>, suffix = true): T {
  const rep = group.rep
  if (group.members.length <= 1) return rep
  return {
    ...rep,
    name: suffix ? `${rep.name}（${group.members.length} 个点位）` : rep.name,
    itemCount: groupItemCount(group),
    minPrice: groupMinPrice(group) ?? rep.minPrice,
  }
}
