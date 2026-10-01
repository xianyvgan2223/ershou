// 验证地点搜索缓存：写入/命中/按校区隔离/TTL 过期/LRU 淘汰
//
// 为什么单独测：搜索配额只有 5000 次/月，缓存一旦失效就会白烧配额，
// 所以命中率相关的分支必须有断言兜着。
//
// 用法： node scripts/verify-place-cache.mjs
import { readFileSync } from 'node:fs'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

// 最小 localStorage 垫片（Node 没有）
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: (k) => store.delete(k),
  clear: () => store.clear(),
}

const mod = await import(
  pathToFileURL(join(root, 'ershou-web/src/utils/placeCache.ts')).href
).catch((e) => {
  console.error('import 失败：' + e.message)
  console.error('请用： node --experimental-strip-types scripts/verify-place-cache.mjs')
  process.exit(2)
})
const { readPlaceCache, writePlaceCache, placeCacheSize, clearPlaceCache } = mod

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

const hits = [
  { name: '桂林电子科技大学花江校区相思湖', address: '灵川县', lng: 110.421228, lat: 25.312689, distance: 456 },
]

clearPlaceCache()
console.log('=== 1. 首次搜索 → 写入缓存 ===')
check('初始缓存为空', readPlaceCache(2, '相思湖') === null)
writePlaceCache(2, '相思湖', hits)
check('写入后能读到', readPlaceCache(2, '相思湖')?.length === 1)
check('坐标原样保存', readPlaceCache(2, '相思湖')[0].lng === 110.421228)
check('缓存条目数为 1', placeCacheSize() === 1)

console.log('\n=== 2. 校区隔离与大小写/空格归一 ===')
check('别的校区读不到（campusId 隔离）', readPlaceCache(1, '相思湖') === null)
writePlaceCache(2, '  相思湖  ', hits)
check('带空格/大小写不同视为同一个键', placeCacheSize() === 1, '实际 ' + placeCacheSize())

console.log('\n=== 3. 空结果不缓存（避免"以后都搜不到"） ===')
writePlaceCache(2, '不存在的名字', [])
check('空结果没有写进缓存', readPlaceCache(2, '不存在的名字') === null)

console.log('\n=== 4. TTL 过期（30 天） ===')
writePlaceCache(2, '过期测试', hits)
const raw = JSON.parse(localStorage.getItem('ershou:place-search:v1'))
const key = Object.keys(raw).find((k) => k.includes('过期测试'))
raw[key].at = Date.now() - 31 * 24 * 60 * 60 * 1000
localStorage.setItem('ershou:place-search:v1', JSON.stringify(raw))
check('31 天前的条目读不到', readPlaceCache(2, '过期测试') === null)
check('过期条目会被顺手清掉', placeCacheSize() === 1, '实际 ' + placeCacheSize())

console.log('\n=== 5. LRU 淘汰（上限 200 条） ===')
clearPlaceCache()
for (let i = 0; i < 230; i++) {
  writePlaceCache(2, '地点' + i, hits)
}
const size = placeCacheSize()
check('条目数被压到上限内', size <= 200, '实际 ' + size)
check('最近写的还在', readPlaceCache(2, '地点229') !== null)
check('最老的被淘汰', readPlaceCache(2, '地点0') === null)

console.log('\n=== 6. 坏数据不炸 ===')
localStorage.setItem('ershou:place-search:v1', '{不是合法 JSON')
check('JSON 坏掉时返回 null 而不是抛异常', readPlaceCache(2, '相思湖') === null)

clearPlaceCache()
console.log('')
if (fail === 0) console.log('全部通过：' + pass + ' 项断言')
else console.log('通过 ' + pass + ' 项，失败 ' + fail + ' 项')
process.exit(fail)
