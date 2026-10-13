/**
 * 「合并点位只能看到一个商品」的端到端验收。
 *
 * 用户原话：*"移动端地图显示的合并点只能看到一个商品"*。
 * 根因：点合并气泡时只按**代表点**的 id 查商品 —— 气泡标着"N 个点位"、
 * 小窗里只有代表点那一件。修法：后端支持 preferPointIds 一次查一组，移动端按组内全部点位查。
 *
 * 这个脚本**自己造现场**（不依赖演示数据）：
 *   1. 找一组会合并的点位（相距 <100 米）；
 *   2. 在组内**每个**点位上各发一件商品；
 *   3. 用接口对比"只查代表点"与"查整组"，证明整组能看到全部；
 *   4. 跑完把商品删掉并重建索引。
 *
 * 用法：node scripts/verify-merged-point-e2e.mjs
 */
import { execFileSync } from 'node:child_process'

const API = process.env.API_BASE ?? 'http://127.0.0.1:8080/api/v1'
const RP = process.env.REDIS_PASSWORD ?? '__REDACTED_DB_PASSWORD__'
const MYSQL_PWD = process.env.MYSQL_PASSWORD ?? '__REDACTED_DB_PASSWORD__'
const MYSQL = 'C:\\Program Files\\MySQL\\MySQL Server 8.0\\bin\\mysql.exe'
const PHONE = '13800000006' // 干净号 id 117

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
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const sql = (q, { silent = false } = {}) => {
  const out = execFileSync(
    MYSQL,
    ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '--default-character-set=utf8mb4', '-N', '-B', '-e', `use ershou; ${q}`],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD }, stdio: silent ? 'ignore' : 'pipe' },
  )
  return out == null ? '' : out.trim()
}

const redis = (...args) =>
  execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', ...args], { encoding: 'utf8' }).trim()

const req = async (method, url, { token, body } = {}) => {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const r = await fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  const text = await r.text()
  try {
    return JSON.parse(text)
  } catch {
    return { code: -1, message: `非 JSON HTTP ${r.status}`, raw: text.slice(0, 120) }
  }
}

function geoDistanceMeters(lng1, lat1, lng2, lat2) {
  const R = 6371008.8
  const rad = (d) => (d * Math.PI) / 180
  const dLat = rad(lat2 - lat1)
  const dLng = rad(lng2 - lng1)
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(lat1)) * Math.cos(rad(lat2)) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.min(1, Math.sqrt(a)))
}

/** 与移动端 point_merge.dart 同一套合并规则（贪心 + 只与组内首个比较 + 100 米） */
function groupPoints(points, radius = 100) {
  const groups = []
  for (const p of points) {
    let joined = false
    for (const g of groups) {
      if (geoDistanceMeters(p.lng, p.lat, g[0].lng, g[0].lat) < radius) {
        g.push(p)
        joined = true
        break
      }
    }
    if (!joined) groups.push([p])
  }
  return groups
}

let createdIds = []

try {
  // ---------- 登录 ----------
  const keys = redis('KEYS', `ershou:sms:limit:${PHONE}:*`).split('\n').map((s) => s.trim()).filter((s) => s.startsWith('ershou:'))
  if (keys.length) redis('DEL', ...keys)
  await req('POST', `${API}/auth/sms-code`, { body: { phone: PHONE } })
  const login = await req('POST', `${API}/auth/login`, { body: { phone: PHONE, code: '123456', schoolId: 1, campusId: 1 } })
  if (login.code !== 0) throw new Error(`登录失败：${login.message}`)
  const token = login.data.token
  const uid = login.data.user.id
  console.log(`  账号 id=${uid}`)

  // ---------- 1. 找一组会合并的点位（优先选"组内点位都有坐标"的）----------
  const raw = sql('SELECT id, name, lng, lat FROM trade_point WHERE lng IS NOT NULL AND lat IS NOT NULL ORDER BY id;')
  const points = raw
    .split('\n')
    .map((line) => {
      const [id, name, lng, lat] = line.split('\t')
      return { id: Number(id), name, lng: Number(lng), lat: Number(lat) }
    })
    .filter((p) => Number.isFinite(p.id) && Number.isFinite(p.lng))

  const groups = groupPoints(points).filter((g) => g.length > 1)
  if (!groups.length) throw new Error('当前数据里没有会合并的点位组，无法验证')
  const group = groups[0]
  console.log(`  选中合并组：${group.map((p) => `#${p.id} ${p.name}`).join(' / ')}`)
  console.log(`  组内点位距离（相对第一个）：${group.slice(1).map((p) => `${Math.round(geoDistanceMeters(p.lng, p.lat, group[0].lng, group[0].lat))}m`).join(', ')}`)

  // ---------- 2. 先记录基线：组内各点位原本各有多少件在售 ----------
  // ⚠️ 不能假设"组内原本没商品"：演示商品重新上架后都带点位，基线可能不是 0。
  //    第一版直接断言 total==1 就是这么挂的（环境变了，断言却写死了）。
  const groupIdsAll = group.map((p) => p.id)
  const baselineOf = async (ids) => {
    const r = await req('GET', `${API}/items?pageNum=1&pageSize=100&preferPointIds=${ids.join(',')}`)
    return r.data?.total ?? 0
  }
  const baselineRep = await baselineOf([group[0].id])
  const baselineGroup = await baselineOf(groupIdsAll)
  console.log('')
  console.log(`  基线：代表点 #${group[0].id} 有 ${baselineRep} 件；整组有 ${baselineGroup} 件`)

  // ---------- 3. 组内每个点位各发一件商品 ----------
  console.log('')
  console.log('=== 在组内每个点位各发一件商品 ===')
  for (const p of group) {
    const res = await req('POST', `${API}/items`, {
      token,
      body: {
        title: `合并点位验证 ${p.id}`,
        description: '自动化测试商品，跑完会删除。',
        categoryId: 1,
        conditionLevel: 2,
        price: 10 + group.indexOf(p),
        negotiable: 1,
        preferPointId: p.id,
        images: ['http://127.0.0.1:9010/ershou/test-upload.jpg'],
      },
    })
    if (res.code !== 0) throw new Error(`在点位 ${p.id} 发布失败：${res.code} ${res.message}`)
    const id = res.data?.itemId ?? res.data?.id
    createdIds.push(id)
    console.log(`  点位 #${p.id} → 商品 ${id}（状态 ${res.data?.status}）`)
  }
  await req('POST', `${API}/search/reindex?sync=true`)

  // ---------- 4. 对比 单点 vs 整组（按增量判断，不看绝对值）----------
  console.log('')
  console.log('=== 对比：只查代表点 vs 查整组 ===')
  const rep = group[0]
  const ids = group.map((p) => p.id)
  const single = await req('GET', `${API}/items?pageNum=1&pageSize=100&preferPointId=${rep.id}`)
  const multi = await req('GET', `${API}/items?pageNum=1&pageSize=100&preferPointIds=${ids.join(',')}`)
  console.log(`  只查代表点 #${rep.id} → total=${single.data?.total}（基线 ${baselineRep} + 1）`)
  console.log(`  查整组 [${ids.join(',')}] → total=${multi.data?.total}（基线 ${baselineGroup} + ${group.length}）`)

  check(
    '只查代表点只多出代表点自己那 1 件（复现"合并点只看得到一个商品"）',
    single.data?.total === baselineRep + 1,
    `实际 ${single.data?.total}，期望 ${baselineRep + 1}`,
  )
  check(
    '查整组能看到组内全部（基线 + 每个点位各 1 件）',
    multi.data?.total === baselineGroup + group.length,
    `实际 ${multi.data?.total}，期望 ${baselineGroup + group.length}`,
  )
  const covered = new Set((multi.data?.records ?? []).map((r) => r.preferPointId))
  check('返回的商品覆盖组内每个点位', ids.every((id) => covered.has(id)), `覆盖 ${[...covered].join(',')}`)

  // ---------- 5. 模拟小窗逻辑：按组查 + 只留在售 ----------
  const onSale = (multi.data?.records ?? []).filter((r) => r.status === 2)
  check(
    '小窗（在售过滤后）能看到组内全部在售商品',
    onSale.length === multi.data?.total,
    `在售 ${onSale.length} / 总数 ${multi.data?.total}`,
  )
} catch (err) {
  fail++
  console.log('')
  console.log(`  [FATAL] ${err.message}`)
} finally {
  console.log('')
  console.log('=== 清理：删除本次造的商品并重建索引 ===')
  if (createdIds.length) {
    sql(`DELETE FROM favorite WHERE item_id IN (${createdIds.join(',')}); DELETE FROM cart_item WHERE item_id IN (${createdIds.join(',')}); DELETE FROM item WHERE id IN (${createdIds.join(',')});`, { silent: true })
    await req('POST', `${API}/search/reindex?sync=true`).catch(() => undefined)
    const left = sql(`SELECT COUNT(*) FROM item WHERE id IN (${createdIds.join(',')});`)
    console.log(`  已删除 ${createdIds.length} 件（残留 ${left} 条）`)
  }
}

console.log('')
console.log(fail === 0 ? `全部通过：${pass} 项断言` : `通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
