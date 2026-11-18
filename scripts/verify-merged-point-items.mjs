// 验证「合并气泡点开能看到组内全部商品」。
//
// 背景：用户反馈"移动端地图显示的合并点只能看到一个商品"。
// 根因：点合并气泡时只按**代表点**的 id 查商品 → 气泡写 3 件、小窗只有 1 件。
// 修法：后端 ItemQueryDTO 支持 preferPointIds（一次查一组），移动端按组内全部点位查。
//
// 本脚本按真实数据来：找出"相距 <100 米且各自有在售商品"的一组点位，
// 对比单点查询与多点查询的条数，证明修复真的能看到全部商品。
import { execFileSync } from 'node:child_process'
import { loadEnvFile } from 'node:process'

try { loadEnvFile(new URL('../.env', import.meta.url)) } catch {}

const API = 'http://127.0.0.1:8080/api/v1'
const MYSQL_PWD = process.env.MYSQL_PASSWORD
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'

let pass = 0
let fail = 0
const check = (d, c, detail = '') => {
  if (c) {
    pass++
    console.log(`  [PASS] ${d}`)
  } else {
    fail++
    console.log(`  [FAIL] ${d}  ${detail}`)
  }
}

const sql = (q) => {
  const out = execFileSync(
    MYSQL,
    ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-N', '-B', '-e', `use ershou; ${q}`],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD } },
  )
  return out == null ? '' : out.trim()
}

const get = async (url) => {
  const r = await fetch(url)
  return r.json()
}

/** 与移动端 point_merge.dart 同一套规则：贪心分组、只与组内第一个点比较、阈值 100 米 */
function geoDistanceMeters(lng1, lat1, lng2, lat2) {
  const R = 6371008.8
  const rad = (d) => (d * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLng = rad(lng2 - lng1)
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)))
}

function groupPoints(points, radius = 100) {
  const groups = []
  for (const p of points) {
    let joined = false
    for (const g of groups) {
      const anchor = g[0]
      if (geoDistanceMeters(p.lng, p.lat, anchor.lng, anchor.lat) < radius) {
        g.push(p)
        joined = true
        break
      }
    }
    if (!joined) groups.push([p])
  }
  return groups
}

// ---- 从库里取"有在售商品的点位" ----
const raw = sql(`
  SELECT p.id, p.name, p.lng, p.lat,
         (SELECT COUNT(*) FROM item i WHERE i.prefer_point_id = p.id AND i.status = 2) AS on_sale
  FROM trade_point p
  WHERE p.lng IS NOT NULL AND p.lat IS NOT NULL
  ORDER BY p.id;`)
const points = raw
  .split('\n')
  .map((line) => {
    const [id, name, lng, lat, onSale] = line.split('\t')
    return { id: Number(id), name, lng: Number(lng), lat: Number(lat), onSale: Number(onSale) }
  })
  .filter((p) => Number.isFinite(p.id))

const withItems = points.filter((p) => p.onSale > 0)
console.log(`有在售商品的点位：${withItems.length} 个`)
withItems.forEach((p) => console.log(`  #${p.id} ${p.name} —— ${p.onSale} 件`))

// ---- 合并分组（只对"有商品的点位"分组，等价于地图上真实看到的气泡）----
const groups = groupPoints(withItems)
const merged = groups.filter((g) => g.length > 1)
console.log('')
console.log(`合并组：${merged.length} 个`)
merged.forEach((g) => {
  const sum = g.reduce((s, p) => s + p.onSale, 0)
  console.log(`  气泡「${g[0].name}（${g.length} 个点位）」应有 ${sum} 件`)
})

if (!merged.length) {
  console.log('')
  console.log('  [SKIP] 当前演示数据里没有会合并的点位，无法验证（不算失败）')
  process.exit(0)
}

console.log('')
console.log('=== 逐组验证：单点查询 vs 多点查询 ===')
for (const g of merged) {
  const rep = g[0]
  const ids = g.map((p) => p.id)
  const expected = g.reduce((s, p) => s + p.onSale, 0)

  const single = await get(`${API}/items?pageNum=1&pageSize=20&preferPointId=${rep.id}`)
  const multi = await get(`${API}/items?pageNum=1&pageSize=20&preferPointIds=${ids.join(',')}`)

  console.log('')
  console.log(`  组 [${ids.join(', ')}]  期望 ${expected} 件`)
  console.log(`    只查代表点 #${rep.id} → total=${single.data?.total}（旧行为，就是那个 bug）`)
  console.log(`    查组内全部        → total=${multi.data?.total}`)

  check(
    `组 [${ids.join(',')}] 多点查询能看到组内全部 ${expected} 件`,
    multi.data?.total === expected,
    `实际 ${multi.data?.total}`,
  )
  check(
    `组 [${ids.join(',')}] 确实比只查代表点多（证明这个 bug 真实存在过）`,
    (multi.data?.total ?? 0) > (single.data?.total ?? 0),
    `单点 ${single.data?.total} / 多点 ${multi.data?.total}`,
  )
  // 返回的商品必须覆盖组内每个点位
  const returnedPoints = new Set((multi.data?.records ?? []).map((r) => r.preferPointId))
  check(
    `组 [${ids.join(',')}] 返回的商品覆盖了组内全部 ${ids.length} 个点位`,
    ids.every((id) => returnedPoints.has(id)),
    `实际覆盖 ${[...returnedPoints].join(',')}`,
  )
}

// ---- 边界：不传/传空不应报错 ----
console.log('')
console.log('=== 边界情况 ===')
const empty = await get(`${API}/items?pageNum=1&pageSize=5`)
check('不传 preferPointIds 仍正常返回（没破坏原查询）', empty.code === 0 && Array.isArray(empty.data?.records))
const tooMany = await get(`${API}/items?pageNum=1&pageSize=5&preferPointIds=${Array.from({ length: 60 }, (_, i) => i + 1).join(',')}`)
check('超过 50 个点位时不报错（截断处理）', tooMany.code === 0, JSON.stringify(tooMany).slice(0, 120))

console.log('')
console.log(fail === 0 ? `全部通过：${pass} 项断言` : `通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
