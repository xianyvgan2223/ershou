// 实测（走 JS API 通道，和浏览器里一模一样）：AMap.Geocoder 在校园点位附近能列出哪些候选
//
// 为什么不用 REST：这个 key 是「Web端(JS API)」类型，打 restapi.amap.com 必然返回
// USERKEY_PLAT_NOMATCH —— 那是平台不匹配的正常反证，不是 key 有问题。
//
// 用法： node scripts/verify-geocode-candidates.mjs
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const env = {}
for (const line of readFileSync(join(root, '.env'), 'utf8').split(/\r?\n/)) {
  const s = line.trim()
  if (!s || s.startsWith('#') || !s.includes('=')) continue
  const i = s.indexOf('=')
  env[s.slice(0, i).trim()] = s.slice(i + 1).trim()
}
const key = env.VITE_AMAP_JS_KEY
const scode = env.VITE_AMAP_JS_SECURITY_CODE

// 最小 window 垫片：loader.js 需要 window / document
const listeners = {}
const define = (name, value) => {
  try {
    Object.defineProperty(globalThis, name, { value, writable: true, configurable: true })
  } catch {
    /* Node 里有的全局属性是只读的，忽略即可 */
  }
}
define('window', globalThis)
define('self', globalThis)
define('navigator', globalThis.navigator ?? { userAgent: 'node' })
define('document', {
  createElement: () => ({ setAttribute() {}, style: {}, appendChild() {} }),
  getElementsByTagName: () => [{ appendChild() {} }],
  head: { appendChild() {} },
  body: { appendChild() {} },
  addEventListener: (t, fn) => (listeners[t] = fn),
  removeEventListener: () => {},
  getElementById: () => null,
  querySelector: () => null,
  querySelectorAll: () => [],
})
define('location', { href: 'http://localhost:5173/', protocol: 'http:', host: 'localhost:5173' })
define('_AMapSecurityConfig', { securityJsCode: scode })

const src = await (await fetch('https://webapi.amap.com/loader.js')).text()
// loader.js 是 UMD，直接在全局作用域求值即可挂上 AMapLoader
new Function(src).call(globalThis)

if (!globalThis.AMapLoader) {
  console.error('AMapLoader 没装上')
  process.exit(2)
}

let AMap
try {
  AMap = await globalThis.AMapLoader.load({ key, version: '2.0', plugins: ['AMap.Geocoder'] })
} catch (e) {
  console.error('加载 JS API 失败：' + (e?.message ?? e))
  process.exit(2)
}
console.log('JS API 加载成功，AMap.Geocoder =', typeof AMap.Geocoder)

const spots = [
  ['花江校区图书馆', 110.418835, 25.313315],
  ['中央食堂正门', 110.417475, 25.312275],
  ['菜鸟驿站(中央食堂旁)', 110.416495, 25.316311],
]

for (const [label, lng, lat] of spots) {
  const list = await new Promise((resolve) => {
    try {
      const geocoder = new AMap.Geocoder({ radius: 500, extensions: 'all' })
      geocoder.getAddress([lng, lat], (status, result) => {
        if (status !== 'complete' || !result?.regeocode) return resolve(null)
        resolve(result.regeocode)
      })
    } catch (e) {
      console.log('  调用异常：' + e.message)
      resolve(null)
    }
  })
  console.log(`\n=== 点击「${label}」附近 (${lng}, ${lat}) ===`)
  if (!list) {
    console.log('  高德没返回结果（Node 环境可能不支持该插件的 DOM 依赖）')
    continue
  }
  console.log(`  格式化地址：${list.formattedAddress}`)
  const pois = [...(list.pois ?? [])].sort(
    (a, b) => Number(a.distance ?? 1e9) - Number(b.distance ?? 1e9),
  )
  console.log(`  附近 POI 共 ${pois.length} 个：`)
  pois.slice(0, 8).forEach((p, i) => {
    console.log(`    ${i + 1}. ${p.name}   ${Math.round(Number(p.distance))} m   ${p.address ?? ''}`)
  })
}
process.exit(0)
