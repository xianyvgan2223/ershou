/**
 * 移动端地图页验收：真实 Chrome 加载**后端下发给 App 的那个 HTML**，
 * 确认高德 JS API 真的把地图渲染出来了（而不只是"key 配上了"）。
 *
 * 为什么必须这么验（README 记过的坑）：
 *   - `.env` 里变量名写错（少了 VITE_ 前缀 / 后端那两个名字没配）时，
 *     页面不会报错，只会**静默**退回自绘底图或显示一行灰字；
 *   - 用 REST 接口验证 JS API Key 是无效的（restapi.amap.com 会返回
 *     USERKEY_PLAT_NOMATCH，那属于正常反证）。
 *   所以判据只能是"浏览器里有没有真的画出瓦片"。
 *
 * 用法：node scripts/verify-amap-mobile-page.mjs
 *   可选：MOBILE_API（默认 http://127.0.0.1:8080）、CAMPUS_ID（默认 2）
 */
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'amap-mobile')
const API = process.env.MOBILE_API ?? 'http://127.0.0.1:8080'
const CAMPUS_ID = process.env.CAMPUS_ID ?? '2'

let pass = 0
let fail = 0
function check(desc, cond, detail = '') {
  if (cond) {
    pass++
    console.log(`  [PASS] ${desc}`)
  } else {
    fail++
    console.log(`  [FAIL] ${desc}  ${detail}`)
  }
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const chromePath = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!chromePath) {
  console.error('找不到 Chrome')
  process.exit(2)
}
async function loadPuppeteer() {
  for (const rel of ['../ershou-web', '..', '.']) {
    try {
      return await import(new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href)
    } catch {
      /* 换下一个 */
    }
  }
  return null
}
const puppeteer = await loadPuppeteer()
if (!puppeteer) {
  console.error('未安装 puppeteer-core')
  process.exit(2)
}

await mkdir(SHOT_DIR, { recursive: true })

console.log('')
console.log(`=== 0. 后端配置自检（App 靠它决定用真地图还是自绘底图）===`)
const statusRes = await fetch(`${API}/api/v1/map/amap-status`).then((r) => r.json())
check('GET /map/amap-status 成功', statusRes.code === 0, JSON.stringify(statusRes).slice(0, 120))
check('后端已配置高德 key', statusRes.data?.configured === true, JSON.stringify(statusRes.data))
console.log(`  jsKeyLength=${statusRes.data?.jsKeyLength} securityCodeLength=${statusRes.data?.securityCodeLength}`)

console.log('')
console.log('=== 1. 下发的 HTML 本身 ===')
const htmlRes = await fetch(`${API}/api/v1/map/amap-html?campusId=${CAMPUS_ID}`)
const html = await htmlRes.text()
check('HTTP 200', htmlRes.status === 200, String(htmlRes.status))
check('不再是"未配置 key"降级页', !html.includes('无法显示真地图'), '仍是降级页')
check('HTML 里已注入 key', /AMapLoader\.load\(\{\s*key:\s*'[0-9a-f]{32}'/.test(html), '没找到 key')
check('HTML 里已注入安全密钥', html.includes('securityJsCode') && !html.includes('%SECURITY_CODE%'), '占位符未替换')
check('没有残留未替换的占位符', !html.includes('%JS_KEY%') && !html.includes('%CAMPUS_ID%'), '有占位符残留')

console.log('')
console.log('=== 2. 真实浏览器渲染（唯一可靠的判据）===')
const browser = await puppeteer.default.launch({
  executablePath: chromePath,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=420,820'],
})
const page = await browser.newPage()
await page.setViewport({ width: 420, height: 820 })
const errors = []
page.on('pageerror', (e) => errors.push(String(e)))

const url = `${API}/api/v1/map/amap-html?campusId=${CAMPUS_ID}`
await page.goto(url, { waitUntil: 'domcontentloaded' })
await page.waitForFunction(() => !!document.querySelector('#map'), { timeout: 15000 })
// 给高德瓦片一点加载时间（首次要下几十个瓦片）
await sleep(6000)

const probe = await page.evaluate(() => {
  const container = document.querySelector('#map')
  const canvas = document.querySelector('#map canvas')
  let canvasPainted = null
  if (canvas && canvas.width > 0 && canvas.height > 0) {
    // 高德 JS API 2.0 把瓦片画进 canvas，DOM 里**没有** <img> 瓦片节点，
    // 所以判据得是"canvas 上真的画了东西"：抽样看像素是不是全同色（全透明/纯色 = 没画）。
    try {
      const ctx = canvas.getContext('2d')
      const w = Math.min(canvas.width, 400)
      const h = Math.min(canvas.height, 400)
      const data = ctx.getImageData(0, 0, w, h).data
      const seen = new Set()
      for (let i = 0; i < data.length; i += 4 * 37) {
        seen.add(`${data[i]},${data[i + 1]},${data[i + 2]},${data[i + 3]}`)
        if (seen.size > 24) break
      }
      canvasPainted = seen.size
    } catch {
      // WebGL 上下文（不是 2d）取不到像素：用 body 文本里的版权信息兜底判断
      canvasPainted = null
    }
  }
  const text = document.body.innerText || ''
  return {
    containerSize: container ? `${container.clientWidth}x${container.clientHeight}` : '无',
    hasAmapClass: !!document.querySelector('.amap-container'),
    hasLayers: !!document.querySelector('.amap-layers'),
    canvasCount: document.querySelectorAll('#map canvas').length,
    canvasPainted,
    // 高德渲染成功才会写入版权行（含 AutoNavi），这是最硬的成功标志
    hasCopyright: /AutoNavi/i.test(text),
    hasScaleBar: /(米|公里)/.test(text),
    markerCount: document.querySelectorAll('.amap-marker, .amap-marker-content').length,
    bodyText: text.slice(0, 200),
  }
})
console.log('  ' + JSON.stringify(probe))
check('高德容器已创建（.amap-container）', probe.hasAmapClass, JSON.stringify(probe))
check('图层已创建（.amap-layers）', probe.hasLayers)
check('地图容器有尺寸', /^\d+x\d+$/.test(probe.containerSize) && !probe.containerSize.startsWith('0x'), probe.containerSize)
check('渲染出高德版权行（AutoNavi = 引擎真的跑起来了）', probe.hasCopyright, probe.bodyText)
check('渲染出比例尺', probe.hasScaleBar, probe.bodyText)
check(
  'canvas 上真的画了内容（不是空白）',
  probe.canvasCount > 0 && (probe.canvasPainted === null || probe.canvasPainted > 3),
  `canvas=${probe.canvasCount} 采样到的不同颜色数=${probe.canvasPainted}`,
)
check('页面没有渲染失败文案', !probe.bodyText.includes('加载失败') && !probe.bodyText.includes('无法显示'), probe.bodyText)

await page.screenshot({ path: path.join(SHOT_DIR, '01-mobile-map.png') })

console.log('')
console.log('=== 3. 点位气泡 ===')
const pointsRes = await fetch(`${API}/api/v1/map/points?campusId=${CAMPUS_ID}`).then((r) => r.json())
const pointCount = (pointsRes.data?.points ?? pointsRes.data ?? []).length ?? 0
console.log(`  接口点位数量 = ${pointCount}`)
check('地图页渲染了点位气泡', pointCount === 0 || probe.markerCount > 0, `markers=${probe.markerCount} points=${pointCount}`)

console.log('')
console.log('=== 4. 页面运行时报错 ===')
const real = errors.filter((e) => !e.includes('favicon') && !e.includes('ResizeObserver'))
check('无明显 JS 报错', real.length === 0, real.slice(0, 3).join(' | '))

await browser.close()
console.log('')
console.log(`截图：${SHOT_DIR}\\01-mobile-map.png`)
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
