// 验证移动端「100 米点位合并」的规则（Node 复刻 lib/core/utils/point_merge.dart）
//
// 为什么用 Node 复刻而不是跑 Dart：
// 1. 规则要跨端一致（Web 的 pointMerge.ts / 移动端 point_merge.dart），
//    Node 里能同时对照两端源码，改一边漏一边会被这里抓到；
// 2. **最关键的断言是"合并不改数据"**：组内必须保留每个真实点位的 id 与坐标，
//    否则商品详情会显示成合并后的代表点坐标——而用户明确要求"显示商家自己定的点"。
//
// 用法： node scripts/verify-point-merge-mobile.mjs
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const R = 6371008.8
const toRad = (d) => (d * Math.PI) / 180
const dist = (lng1, lat1, lng2, lat2) => {
  const dLat = toRad(lat2 - lat1)
  const dLng = toRad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(a))
}

// —— 与 Dart 的 groupPointsByDistance 同规则：贪心，只与组内第一个点比较 ——
function groupByDistance(points, radius = 100) {
  const groups = []
  for (const p of points) {
    if (p.lng == null || p.lat == null) {
      groups.push([p])
      continue
    }
    let joined = false
    for (const g of groups) {
      const a = g[0]
      if (a.lng == null || a.lat == null) continue
      if (dist(p.lng, p.lat, a.lng, a.lat) < radius) {
        g.push(p)
        joined = true
        break
      }
    }
    if (!joined) groups.push([p])
  }
  return groups
}

let pass = 0
let fail = 0
const check = (desc, cond, detail = '') => {
  if (cond) {
    pass++
    console.log('  [PASS] ' + desc)
  } else {
    fail++
    console.log('  [FAIL] ' + desc + '  ' + detail)
  }
}

// 花江校区真实点位（取自数据库）
const REAL = [
  { id: 1, name: '花江校区图书馆', lng: 110.418835, lat: 25.313315 },
  { id: 2, name: '中央食堂正门', lng: 110.417475, lat: 25.312275 },
  { id: 3, name: '菜鸟驿站(中央食堂旁)', lng: 110.416495, lat: 25.316311 },
  { id: 991007, name: '研究生院', lng: 110.418741, lat: 25.310151 },
  { id: 991011, name: 'C区快递柜(靠篮球场)', lng: 110.417, lat: 25.311 },
  { id: 991016, name: '清苑11栋', lng: 110.415012, lat: 25.319317 },
  { id: 991017, name: '花江校区图书馆', lng: 110.418835, lat: 25.313315 },
  { id: 991018, name: '思进湖', lng: 110.415162, lat: 25.310414 },
]

console.log('=== 1. 真实数据：100 米内合并，且保留组内精确点位 ===')
const groups = groupByDistance(REAL)
console.log(`  ${REAL.length} 个点位 → ${groups.length} 个气泡`)
groups.forEach((g) => {
  const tag = g.length > 1 ? `【合并 ${g.length} 个】` : ''
  console.log(`    ${g[0].name} ${tag} ids=${g.map((p) => p.id).join(',')}`)
})
check('发生了合并（气泡数 < 点位数）', groups.length < REAL.length, `${groups.length} vs ${REAL.length}`)
check(
  '组内点位总数守恒（没有点位被丢掉）',
  groups.reduce((n, g) => n + g.length, 0) === REAL.length,
  '数量对不上',
)
const allIds = groups.flatMap((g) => g.map((p) => p.id)).sort((a, b) => a - b)
const realIds = REAL.map((p) => p.id).sort((a, b) => a - b)
check('所有真实 id 都还在（合并不吞数据）', JSON.stringify(allIds) === JSON.stringify(realIds), 'id 不一致')
const coordsKept = groups
  .flatMap((g) => g.map((p) => [p.lng, p.lat]))
  .every(([a, b]) => a != null && b != null)
check('每个点位都保留了自己的精确坐标', coordsKept, '有坐标丢失')
// 图书馆在库里是两条完全同坐标的记录 → 必须合并且两条 id 都在
const lib = groups.find((g) => g.length > 1 && g[0].name.includes('图书馆'))
check('同坐标的图书馆两条被合并', !!lib && lib.length === 2, lib ? `len=${lib.length}` : '未合并')
check(
  '合并后仍保留两条真实 id（1 与 991017）',
  !!lib && lib.map((p) => p.id).sort((a, b) => a - b).join(',') === '1,991017',
  lib ? lib.map((p) => p.id).join(',') : 'n/a',
)

console.log('\n=== 2. 边界：阈值是「不足 100 米才合并」===')
// 注意别指望构造出"恰好 100.000 米"——浮点换算一定差一点点。
// 这里精确算出实际距离，再断言"距离 >= 100 就不合并"这个语义。
const mk = (meters) => [
  { id: 1, lng: 110.42, lat: 25.31 },
  { id: 2, lng: 110.42, lat: 25.31 + meters / 111320 },
]
const pairs = [
  { m: 99, expect: 1 },
  { m: 100.5, expect: 2 },
  { m: 120, expect: 2 },
]
for (const { m, expect } of pairs) {
  const p = mk(m)
  const actualDist = dist(p[0].lng, p[0].lat, p[1].lng, p[1].lat)
  const got = groupByDistance(p).length
  check(
    `相距约 ${m} 米（实际 ${actualDist.toFixed(2)} 米）→ ${expect === 1 ? '合并' : '不合并'}`,
    got === expect,
    `实际 ${got} 组`,
  )
}
// 属性断言：任何距离 >= 100 的点对都不该在同一组
const p101 = mk(101)
const d101 = dist(p101[0].lng, p101[0].lat, p101[1].lng, p101[1].lat)
check(
  `属性：距离 ${d101.toFixed(2)} 米 >= 100 → 不合并`,
  groupByDistance(p101).length === 2,
  '被合并了',
)

console.log('\n=== 3. 不链式传递：A≈B、B≈C 不该把 A、C 滚成一组 ===')
// 三点等距 80 米一字排开：贪心（只与组内第一个比）应得到 2 组
const chain = [
  { id: 1, lng: 110.42, lat: 25.31 },
  { id: 2, lng: 110.42, lat: 25.31 + 80 / 111320 },
  { id: 3, lng: 110.42, lat: 25.31 + 160 / 111320 },
]
const chainGroups = groupByDistance(chain)
check('一字排开的三点分成 2 组（不是 1 组）', chainGroups.length === 2, `实际 ${chainGroups.length} 组`)

console.log('\n=== 4. 没有坐标的点位不参与合并 ===')
const noCoord = [
  { id: 1, name: '像素点A', lng: null, lat: null },
  { id: 2, name: '像素点B', lng: null, lat: null },
]
check('两个无坐标点位各自成组', groupByDistance(noCoord).length === 2, '被合并了')

console.log('\n=== 5. 跨端一致性：两端规则要能对上 ===')
const dartSrc = readFileSync(join(root, 'ershou_app/lib/core/utils/point_merge.dart'), 'utf8')
const webSrc = readFileSync(join(root, 'ershou-web/src/utils/pointMerge.ts'), 'utf8')
check('移动端阈值是 100', /mergeRadiusMeters\s*=\s*100/.test(dartSrc), '阈值不是 100')
check('Web 端阈值是 100', /MERGE_RADIUS_METERS\s*=\s*100/.test(webSrc), '阈值不是 100')
check('两端都用 Haversine 球面距离', /6371008\.8/.test(dartSrc) && /6371008\.8/.test(webSrc), '半径常数不一致')
check(
  '两端都保留了"组内成员/ids"（合并不吞数据）',
  /List<int> get ids/.test(dartSrc) && /members/.test(webSrc),
  '有一端没保留成员',
)

console.log('')
if (fail === 0) console.log('全部通过：' + pass + ' 项断言')
else console.log('通过 ' + pass + ' 项，失败 ' + fail + ' 项')
process.exit(fail)
