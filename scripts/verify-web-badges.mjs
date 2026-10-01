// 验收脚本：用无头 Chrome 登录验收账号，检查两处提示
//   1. 进购物车页后，导航栏「购物车」上的件数气泡应当消失
//   2. 「我的面交」两个 tab 上应当带待办数字（我卖出的 = 有买家下单）
//
// 依赖：puppeteer-core + 本机 Chrome。用 data 目录里的 build 产物或 dev server 都行。
// 用法： node scripts/verify-web-badges.mjs
import { existsSync } from 'node:fs'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build')
const BASE = process.env.WEB_BASE ?? 'http://localhost:5173'
const PHONE = process.env.WEB_PHONE ?? '13800001234'
const TOKEN = process.env.WEB_TOKEN

const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
]
const chromePath = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!chromePath) {
  console.error('找不到 Chrome，跳过（这个脚本需要真实浏览器跑 Vue 应用）')
  process.exit(2)
}
// 从**脚本所在目录**往上找 node_modules：脚本在仓库根目录跑，
// 而 puppeteer-core 装在 ershou-web 下，直接 import 解析不到
async function loadPuppeteer() {
  for (const rel of ['../ershou-web', '..', '.']) {
    try {
      const url = new URL(rel + '/node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js', import.meta.url)
      return await import(url.href)
    } catch {
      /* 换下一个候选 */
    }
  }
  return null
}
const puppeteer = await loadPuppeteer()
if (!puppeteer) {
  console.error('未安装 puppeteer-core：pnpm add -D puppeteer-core')
  process.exit(2)
}

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

await mkdir(SHOT_DIR, { recursive: true })
const browser = await puppeteer.default.launch({
  executablePath: chromePath,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1280,900'],
})
const page = await browser.newPage()
await page.setViewport({ width: 1280, height: 900 })

// 注入登录态：应用把 token 放在 localStorage 的 ershou.token（见 utils/request 与 stores/user）
if (TOKEN) {
  await page.goto(BASE, { waitUntil: 'domcontentloaded' })
  await page.evaluate(
    (t) => {
      // KEY 必须与 utils/request.ts 的 TOKEN_KEY 一致：ershou_token（下划线，不是点）
      localStorage.setItem('ershou_token', t)
    },
    TOKEN,
  )
}

try {
  console.log('')
  console.log('=== 1. 导航栏「购物车」气泡 ===')
  await page.goto(`${BASE}/`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(
    () => document.querySelectorAll('.nav__link').length > 0,
    { timeout: 15000 },
  )
  // 首页应当能看到购物车件数气泡（购物车里有 1 件）
  const onHome = await page.$$eval('.nav__link', (links) =>
    links.map((a) => ({
      label: a.textContent.trim(),
      dot: a.querySelector('.nav__dot')?.textContent.trim() ?? null,
    })),
  )
  const cartOnHome = onHome.find((x) => x.label.startsWith('购物车'))
  check(`首页能看到购物车气泡（实际 ${cartOnHome?.dot ?? '无'}）`, cartOnHome?.dot === '1', JSON.stringify(cartOnHome))
  await page.screenshot({ path: path.join(SHOT_DIR, 'web-nav-home.png') })

  await page.goto(`${BASE}/cart`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.cart'), { timeout: 15000 })
  const onCart = await page.$$eval('.nav__link', (links) =>
    links.map((a) => ({
      label: a.textContent.trim(),
      dot: a.querySelector('.nav__dot')?.textContent.trim() ?? null,
    })),
  )
  const cartOnCart = onCart.find((x) => x.label.startsWith('购物车'))
  check('进入购物车页后气泡消失', cartOnCart?.dot === null, JSON.stringify(cartOnCart))
  await page.screenshot({ path: path.join(SHOT_DIR, 'web-nav-cart.png') })

  console.log('')
  console.log('=== 2. 「我的面交」tab 上的待办数字 ===')
  await page.goto(`${BASE}/orders`, { waitUntil: 'networkidle2' })
  await page.waitForFunction(() => !!document.querySelector('.el-tabs'), { timeout: 15000 })
  // 给 store 的 loadTodo 一点时间
  await new Promise((r) => setTimeout(r, 2500))
  const tabs = await page.$$eval('.el-tabs__item', (items) =>
    items.map((el) => ({
      text: el.textContent.replace(/\s+/g, ' ').trim(),
      badge: el.querySelector('.el-badge__content')?.textContent.trim() ?? null,
    })),
  )
  console.log('  tab 实际渲染：' + JSON.stringify(tabs))
  const sellerTab = tabs.find((t) => t.text.includes('我卖出的'))
  check('存在「我卖出的」tab', !!sellerTab, JSON.stringify(tabs))
  check(
    `「我卖出的」带待办数字（实际 ${sellerTab?.badge ?? '无'}）`,
    sellerTab?.badge && sellerTab.badge !== '0',
    JSON.stringify(sellerTab),
  )
  await page.screenshot({ path: path.join(SHOT_DIR, 'web-orders-tabs.png') })

  // 与购物车同样的规则：人在这一页时导航上的待办红点要收起来
  const onOrders = await page.$$eval('.nav__link', (links) =>
    links.map((a) => ({
      label: a.textContent.trim(),
      dot: a.querySelector('.nav__dot')?.textContent.trim() ?? null,
    })),
  )
  const ordersOnOrders = onOrders.find((x) => x.label.startsWith('我的面交'))
  check('进入我的面交页后导航红点消失', ordersOnOrders?.dot === null, JSON.stringify(ordersOnOrders))
  // 但「消息」的未读红点不该被这条规则波及
  const chatOnOrders = onOrders.find((x) => x.label.startsWith('消息'))
  check('「消息」红点不受影响（未读才显示）', chatOnOrders !== undefined, JSON.stringify(onOrders))
} finally {
  await browser.close()
}

console.log('')
if (fail === 0) console.log(`全部通过：${pass} 项断言`)
else console.log(`通过 ${pass} 项，失败 ${fail} 项`)
process.exit(fail)
