// 验证：网页端地图选点用的是"水滴针"（与移动端同一套画法），不再是难看的「0」气泡。
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'

const API = 'http://127.0.0.1:8080/api/v1'
const WEB = 'http://localhost:5173'
const OUT = 'E:\\ershou\\.build\\pick-pin'
await mkdir(OUT, { recursive: true })
const RP = process.env.REDIS_PASSWORD ?? '__REDACTED_DB_PASSWORD__'
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const post = async (u, b, t) => {
  const h = { 'Content-Type': 'application/json' }
  if (t) h.Authorization = 'Bearer ' + t
  const r = await fetch(u, { method: 'POST', headers: h, body: JSON.stringify(b) })
  return r.json()
}
try {
  const ks = execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', 'KEYS', 'ershou:sms:limit:13800000007:*'], { encoding: 'utf8' })
    .split('\n').map((s) => s.trim()).filter((s) => s.startsWith('ershou:'))
  if (ks.length) execFileSync('docker', ['exec', 'ershou-redis', 'redis-cli', '-a', RP, '--no-auth-warning', 'DEL', ...ks], { stdio: 'ignore' })
} catch {}
await post(`${API}/auth/sms-code`, { phone: '13800000007' })
const l = await post(`${API}/auth/login`, { phone: '13800000007', code: '123456', schoolId: 1, campusId: 1 })

let pp = null
for (const rel of ['../ershou-web', '..', '.']) {
  try {
    pp = await import(new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url).href)
    break
  } catch {}
}
const CHROME = ['C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe', 'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe'].find(existsSync)
const br = await pp.default.launch({ executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--disable-gpu'] })
const page = await br.newPage()
await page.setViewport({ width: 1400, height: 1000 })
const errs = []
page.on('pageerror', (e) => errs.push(String(e)))

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

await page.goto(WEB, { waitUntil: 'domcontentloaded' })
await page.evaluate((t) => localStorage.setItem('ershou_token', t), l.data.token)
await page.goto(`${WEB}/publish`, { waitUntil: 'networkidle2' })
await sleep(2500)

console.log('=== 打开选点弹窗 ===')
// 先确认按钮真的可见（rect 非零）——直接把坐标丢给 mouse.click，遇到"按钮在折叠区/视口外"
// 会静默点空，测试就假失败。所以这里先 scrollIntoView + 校验尺寸。
const openBtn = await page.evaluate(() => {
  const el = Array.from(document.querySelectorAll('button')).find((b) => b.textContent.includes('地图上新增'))
  if (!el) return { found: false }
  el.scrollIntoView({ block: 'center' })
  const r = el.getBoundingClientRect()
  return {
    found: true,
    x: r.x + r.width / 2,
    y: r.y + r.height / 2,
    w: r.width,
    h: r.height,
    visible: r.width > 0 && r.height > 0,
    disabled: el.disabled === true,
  }
})
console.log('  按钮：', JSON.stringify(openBtn))
check('发布页有「地图上新增」按钮且可见可点', openBtn.found && openBtn.visible && !openBtn.disabled, JSON.stringify(openBtn))
if (openBtn.found && openBtn.visible) {
  await page.mouse.click(openBtn.x, openBtn.y)
  await sleep(5500)
}
const dlgInfo = await page.evaluate(() => {
  const dlg = document.querySelector('.el-dialog, .el-drawer')
  return { open: !!dlg, cls: dlg?.className ?? '', hasMap: !!document.querySelector('.picker__map') }
})
console.log('  弹窗：', JSON.stringify(dlgInfo))
check('选点弹窗已打开', dlgInfo.open, JSON.stringify(dlgInfo))

// 等地图容器就绪，然后在**地图中间**点一下（触发 pick）
console.log('')
console.log('=== 在地图上点一个位置 ===')
const mapBox = await page.evaluate(() => {
  const el = document.querySelector('.picker__map .amap-campus, .picker__map')
  if (!el) return null
  const r = el.getBoundingClientRect()
  return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height }
})
check('找到地图容器', !!mapBox, JSON.stringify(mapBox))
if (mapBox) {
  await page.mouse.click(mapBox.x, mapBox.y)
  await sleep(1200)
  // 反地理编码可能慢，多等一会
  await sleep(3500)
  await page.screenshot({ path: `${OUT}\\01-after-pick.png`, fullPage: false })

  const pin = await page.evaluate(() => {
    const el = document.querySelector('.amap-campus__pickmark')
    const bubble = document.querySelector('.amap-campus__marker[data-point-id="-1"] .amap-campus__bubble')
    if (!el) return { hasPin: false, hasBubble: !!bubble, bubbleText: bubble?.textContent ?? null }
    const r = el.getBoundingClientRect()
    const head = el.querySelector('.amap-campus__pickmark-head')
    const foot = el.querySelector('.amap-campus__pickmark-foot')
    const headStyle = head ? getComputedStyle(head) : null
    return {
      hasPin: true,
      box: { w: Math.round(r.width), h: Math.round(r.height) },
      headBg: headStyle?.backgroundColor,
      headRadius: headStyle?.borderRadius,
      headBorder: headStyle?.borderTopWidth + ' ' + headStyle?.borderTopColor,
      hasFoot: !!foot,
      hasBubble: !!bubble,
      bubbleText: bubble?.textContent ?? null,
      // 针尖是否落在地点上：伪元素 ::after 从 top:20px 起
      markerAnchor: document.querySelector('.amap-campus__marker[data-point-id="-1"]')?.className,
    }
  })
  console.log('  选点标记：', JSON.stringify(pin))

  check('地图上出现了选点针（.amap-campus__pickmark）', pin.hasPin, JSON.stringify(pin))
  check('**没有**渲染成数字气泡（避免那个难看的「0」）', !pin.hasBubble, `bubbleText=${pin.bubbleText}`)
  check('针的尺寸符合水滴形状（26×36，比气泡高）', pin.box?.w === 26 && pin.box?.h === 36, JSON.stringify(pin.box))
  check('针头是橙色圆头 + 白描边（与移动端同一套画法）', /rgb\(255, 107, 53\)/.test(pin.headBg ?? '') && pin.headRadius === '50%', `${pin.headBg} / ${pin.headRadius}`)
  check('有地面小椭圆（纵深感）', pin.hasFoot)
}

console.log('')
console.log('=== 对照：地图页的普通点位仍应是数字气泡 ===')
await page.goto(`${WEB}/map`, { waitUntil: 'networkidle2' })
await sleep(4500)
const mapPage = await page.evaluate(() => {
  const bubbles = Array.from(document.querySelectorAll('.amap-campus__marker .amap-campus__bubble'))
  return {
    bubbleCount: bubbles.length,
    samples: bubbles.slice(0, 5).map((b) => b.textContent.trim()),
    pinCount: document.querySelectorAll('.amap-campus__pickmark').length,
  }
})
console.log('  地图页：', JSON.stringify(mapPage))
check('地图页仍显示数字气泡', mapPage.bubbleCount > 0, JSON.stringify(mapPage))
check('地图页**没有**误用选点针', mapPage.pinCount === 0, `pinCount=${mapPage.pinCount}`)
await page.screenshot({ path: `${OUT}\\02-map-page.png`, fullPage: false })

console.log('')
const real = errs.filter((e) => !/favicon|Devtools|ResizeObserver/.test(e))
check('无 JS 报错', real.length === 0, real.slice(0, 2).join(' | '))

await br.close()
console.log('')
console.log(`截图：${OUT}`)
console.log(fail === 0 ? `全部通过：${pass} 项断言` : `通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail === 0 ? 0 : 1)
