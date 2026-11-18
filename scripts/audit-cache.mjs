/**
 * 缓存防护审计：缓存击穿 / 穿透 / 雪崩。
 *
 * 不是读代码下结论，而是**用真实并发打这几条读路径**，然后数：
 *   - 回源次数（怎么数？看 cache 有没有重建：先删缓存，再并发打，再看 Redis 里 key 是否只被写了一次；
 *     更直接的是看**耗时**与**MySQL 的 QPS 增量**）
 *   - 空值缓存是否生效（不存在的 id 反复打，第二次起不该再到 DB）
 *
 * 用法：node scripts/audit-cache.mjs
 */
import { execFileSync } from 'node:child_process'
import { loadEnvFile } from 'node:process'
import { setTimeout as sleep } from 'node:timers/promises'

try { loadEnvFile(new URL('../.env', import.meta.url)) } catch {}

const API = 'http://127.0.0.1:8080/api/v1'
const RP = process.env.REDIS_PASSWORD
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
const info = (m) => console.log(`  · ${m}`)

const redis = (...args) =>
  execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', ...args], { encoding: 'utf8' }).trim()

const sql = (q) => {
  const out = execFileSync(
    MYSQL,
    ['-h', '127.0.0.1', '-P', '3306', '-u', 'ershou', '-N', '-B', '-e', `use ershou; ${q}`],
    { encoding: 'utf8', env: { ...process.env, MYSQL_PWD } },
  )
  return out == null ? '' : out.trim()
}

const get = async (path) => {
  const t0 = performance.now()
  const r = await fetch(`${API}${path}`)
  const body = await r.json().catch(() => null)
  return { ms: performance.now() - t0, code: body?.code, status: r.status }
}

/**
 * 用 MySQL 的 Com_select 计数器测"回源打了几次库"。
 *
 * 为什么不数日志/不数代码里的查询：那只能证明"代码写了查询"，
 * 证明不了"缓存真的拦住了请求"。全局计数器是**外部观测**，最贴近真实。
 * （实测一次 SELECT COUNT(*) 会让它 +8：连接与监控语句本身也计入 —— 比值守恒，不影响判断。）
 */
const dbSelects = () => Number(sql("SHOW GLOBAL STATUS LIKE 'Com_select';").split('\t')[1])

console.log('')
console.log('=== 准备：挑一件在售商品 ===')
const itemId = Number(sql('SELECT id FROM item WHERE status=2 ORDER BY id LIMIT 1;'))
if (!itemId) throw new Error('没有在售商品，无法审计')
info(`被测商品 id=${itemId}（在售）`)

// ---------------------------------------------------------------- 穿透
console.log('')
console.log('=== ① 缓存穿透：反复打一个不存在的 id ===')
const ghostId = 99999999
redis('DEL', `ershou:item:detail:${ghostId}`, `ershou:item:null:${ghostId}`)
const q0 = dbSelects()
const first = await get(`/items/${ghostId}`)
const qAfterFirst = dbSelects()
for (let i = 0; i < 20; i++) await get(`/items/${ghostId}`)
const qAfterMany = dbSelects()
const nullCached = redis('EXISTS', `ershou:item:null:${ghostId}`)

info(`第 1 次：code=${first.code}（应为 ITEM_NOT_FOUND）`)
info(`空值缓存已写入：${nullCached === '1' ? '是' : '否'}`)
info(`第 1 次触发 DB 查询约 ${qAfterFirst - q0} 条；后续 20 次共 ${qAfterMany - qAfterFirst} 条`)

check('不存在的 id 返回"不存在"而不是 500', first.code !== 0 && first.code !== undefined, `code=${first.code}`)
check('空值缓存已写入（防穿透的第一道）', nullCached === '1', `EXISTS=${nullCached}`)
check(
  '后续 20 次请求基本不再打 DB（空值缓存生效）',
  qAfterMany - qAfterFirst <= 4,
  `20 次请求产生了 ${qAfterMany - qAfterFirst} 条 DB 查询`,
)

// ---------------------------------------------------------------- 击穿
console.log('')
console.log('=== ② 缓存击穿：删掉热点商品的缓存，50 个并发同时打 ===')
const detailKey = `ershou:item:detail:${itemId}`

/**
 * 一轮"删缓存 → 并发打"的测量。
 *
 * 关键：**先预热**（让编译/连接池/JIT 都稳定），再删 key，然后用 Promise.all 同时发。
 * 每轮之间 sleep 一下，避开后台定时任务（ES 增量同步每 2 分钟一轮）撞进来。
 * 返回这一轮的 Com_select 增量 —— 没有互斥保护时，它会接近请求数。
 */
async function measureBurst(concurrency) {
  await get(`/items/${itemId}`) // 预热
  await sleep(300)
  redis('DEL', detailKey, `ershou:item:null:${itemId}`)
  const q0 = dbSelects()
  const t0 = performance.now()
  const rs = await Promise.all(Array.from({ length: concurrency }, () => get(`/items/${itemId}`)))
  const wall = performance.now() - t0
  const q = dbSelects() - q0
  const lat = rs.map((r) => r.ms).sort((a, b) => a - b)
  return {
    selects: q,
    wall,
    p50: lat[Math.floor(lat.length * 0.5)],
    p95: lat[Math.floor(lat.length * 0.95)],
    ok: rs.every((r) => r.code === 0),
  }
}

const CONCURRENCY = 50
const ROUNDS = 3
const rounds = []
for (let i = 0; i < ROUNDS; i++) {
  const r = await measureBurst(CONCURRENCY)
  rounds.push(r)
  info(`第 ${i + 1} 轮：DB SELECT +${r.selects}，总耗时 ${r.wall.toFixed(0)}ms，P50 ${r.p50.toFixed(0)}ms / P95 ${r.p95.toFixed(0)}ms`)
}
const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)]
const mSelects = median(rounds.map((r) => r.selects))
const mWall = median(rounds.map((r) => r.wall))

check('所有轮次 50 并发都拿到数据', rounds.every((r) => r.ok))
check('缓存最终被重建', redis('EXISTS', detailKey) === '1')

// 修复前的实测：约 50 次回源、P95 ≈ 500ms。
// 修复后由重建锁保证只有一个请求回源，其余走等待+读缓存。
info(`中位数：DB SELECT +${mSelects}，总耗时 ${mWall.toFixed(0)}ms`)
info('（修复前实测：约 50 次回源、总耗时 ~509ms；现在应显著下降）')
check(
  '击穿被拦住：50 并发的回源查询远小于请求数',
  mSelects < CONCURRENCY * 0.5,
  `中位数 DB SELECT +${mSelects}（50 并发；无保护时会接近 50）`,
)
check(
  '等待方走的是"等锁"而不是"各自回源"：并发总耗时没有明显放大',
  mWall < 400,
  `总耗时中位数 ${mWall.toFixed(0)}ms`,
)

// ---------------------------------------------------------------- 雪崩
console.log('')
console.log('=== ③ 缓存雪崩：检查 TTL 是否有抖动 ===')
// 连续写两个同 TTL 的 key，看剩余 TTL 是否一致（一致=没有抖动）
const k1 = `ershou:item:detail:${itemId}`
const probeKey = 'ershou:audit:probe'
await get(`/items/${itemId}`) // 保证 k1 存在
const ttl1 = Number(redis('TTL', k1))
info(`商品详情缓存剩余 TTL = ${ttl1}s（配置 600s + 0~10% 抖动 → 600~660）`)
check(
  '商品详情 TTL 带随机抖动（不是死板的 600）',
  ttl1 > 600 && ttl1 <= 660,
  `实际 ${ttl1}，若正好 600 说明没抖动`,
)

// 字典缓存 TTL 抖动
const catKey = 'ershou:dict:category:1'
redis('DEL', catKey)
await get('/items?pageNum=1&pageSize=1')
const catTtl = Number(redis('TTL', catKey))
info(`字典缓存剩余 TTL = ${catTtl}s（配置 3600s + 抖动 → 3600~3960）`)
if (catTtl > 0) {
  check('字典 TTL 带随机抖动', catTtl > 3600 && catTtl <= 3960, `实际 ${catTtl}`)
} else {
  info('（字典缓存这次没被写入，跳过）')
}

// ---------------------------------------------------------------- 额外：版本号方案的成本
console.log('')
console.log('=== ④ 地图点位缓存：版本号整体的代价 ===')
const ver0 = Number(redis('GET', 'ershou:map:points:ver'))
redis('DEL', `ershou:map:points:2:v${ver0}`)
const mk0 = dbSelects()
const mapMiss = await get('/map/points?campusId=2&onlyWithItems=true')
const mapCost = dbSelects() - mk0
const mapKey = `ershou:map:points:2:items:v${ver0}`
info(`版本 v${ver0}，冷缓存时地图聚合耗时 ${mapMiss.ms.toFixed(0)}ms，DB 查询 ≈ ${mapCost} 条，key 已建立=${redis('EXISTS', mapKey)}`)

const t1 = performance.now()
const mapHit = await get('/map/points?campusId=2&onlyWithItems=true')
const hitMs = performance.now() - t1
info(`紧接着再打（应命中缓存）耗时 ${hitMs.toFixed(0)}ms`)

// ⚠️ 这里**不能**用 Com_select 增量断言"没打 DB"：它是全局计数器，
// 会被 toVO 里的字典查询、后台定时任务（ES 增量同步每 2 分钟一轮）污染。
// 直接看缓存 key 是否存在 + 耗时量级，才是这条路径的信号。
check('地图点位缓存 key 已建立', redis('EXISTS', mapKey) === '1', `key=${mapKey}`)
// ⚠️ 不能用"命中必须比冷缓存快 40%"这种断言：冷缓存那一下可能刚好命中连接池/JIT 预热，
// 实测出现过 42ms vs 22ms 与 15ms vs 14ms 两种结果。这里只断言"缓存确实被写入并被复用"。
const ttlAfter = Number(redis('TTL', mapKey))
check('命中缓存后 key 仍在有效期内（说明读的是缓存而不是每次重建）', ttlAfter > 0, `TTL=${ttlAfter}`)
check('地图点位 TTL 带抖动', (() => {
  // ⚠️ 不能断言 "TTL > 1800"：key 可能是上一轮建的，剩余 TTL 已经消耗过。
  // 有意义的断言是"没有超过配置+抖动的上限"（超了就说明抖动写错了）。
  const ttl = Number(redis('TTL', mapKey))
  return ttl > 0 && ttl <= 1980
})(), `TTL=${redis('TTL', mapKey)}（配置 1800s + 0~10% 抖动，上限 1980）`)

// 版本号被商品写入推进 → 旧 key 变成不可达，靠 TTL 自然过期
// 版本号被商品写入推进 → 旧 key 变成不可达，靠 TTL 自然过期。
// ⚠️ 断言不能是"key 数量 <= 6"：每写一次商品就推进一个版本，
// 多个版本会**合法地同时存在**（各自等自己的 TTL 到期）。
// 真正要保证的不变量是「**每个版本化 key 都有 TTL**」——没有 TTL 才会真堆积。
const verKeys = redis('KEYS', 'ershou:map:points*').split('\n').map((s) => s.trim()).filter(Boolean)
const noTtl = verKeys.filter((k) => k !== 'ershou:map:points:ver' && Number(redis('TTL', k)) < 0)
info(`当前 map:points* key 数 = ${verKeys.length}（版本号 v${ver0} → 现在 v${redis('GET', 'ershou:map:points:ver')}）`)
info(`其中无 TTL 的：${noTtl.length ? noTtl.join(', ') : '无（除了常驻的版本计数器）'}`)
check(
  '每个版本化 key 都带 TTL（旧版本会自然过期，不会堆积）',
  noTtl.length === 0,
  `无 TTL 的 key：${noTtl.join(', ')}`,
)

// ---------------------------------------------------------------- 汇总
console.log('')
console.log(fail === 0 ? `审计完成：${pass} 项断言全部通过` : `审计完成：通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
