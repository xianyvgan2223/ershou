// 第 3 轮「平台代卖 · 独立运营台」验收 · Web 视觉/布局/兼容脚本
//
// 对应 `智能体团队/第3轮-平台运营台-设计.md`：
//   - 第二节视觉硬指标（.pc 布局 / 248px 深色侧栏 / 64px 顶栏 / 通栏内容区 / KPI 大卡 / 表格无横向截断）
//   - 第四节路由与页面契约（7 页 + 旧地址 redirect）
//   - 第五节验收标准 3（7 张 1440×900 截图 + 1 张 1920×1080 概览）与 4（兼容）
//
// 依赖：puppeteer-core（ershou-web/node_modules）+ 本机 Chrome/Edge；前端 Vite dev server（默认 http://localhost:5173）；
//       后端 8080（数据来源）。管理员 token 优先级：
//         1) env WEB_ADMIN_TOKEN
//         2) .build/qa/platform-qa-tokens.json 里 phone=19900000102 的 token（第 2 轮 QA 脚本产物，BOM 已处理）
//         3) .build/console/admin-token.txt（纯文本 token）
//
// 用法：
//   node scripts/verify-console-web.mjs --dry-run     # 只看计划（不启动浏览器）
//   node scripts/verify-console-web.mjs               # 全量（截图 + 断言，写 .build/console/）
//   WEB_BASE=http://localhost:5173 node scripts/verify-console-web.mjs
//
// 输出：.build/console/console-*.png（8 张）、.build/console/console-web-result.json
// 退出码：0=全通过；2=环境缺失（Chrome/puppeteer/dev server/token）；>0=失败断言数

import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const OUT_DIR = path.join(ROOT, '.build', 'console')
const TOKEN_ROUND2 = path.join(ROOT, '.build', 'qa', 'platform-qa-tokens.json')
const TOKEN_FILE = path.join(OUT_DIR, 'admin-token.txt')
const BASE = (process.env.WEB_BASE ?? 'http://localhost:5173').replace(/\/$/, '')
const ADMIN_PHONE = process.env.WEB_ADMIN_PHONE ?? '19900000102'
const DRY = process.argv.includes('--dry-run')
const NOMINAL_SIDEBAR = 248
const NOMINAL_VIEWPORT = { width: 1440, height: 900 }
const BIG_VIEWPORT = { width: 1920, height: 1080 }

const PAGES = [
  { name: 'overview',   path: '/console/platform',            title: '运营概览', file: 'console-overview-1440.png',   expectTable: true },
  { name: 'items',      path: '/console/platform/items',      title: '平台商品', file: 'console-items-1440.png',      expectTable: true },
  { name: 'points',     path: '/console/platform/points',     title: '代卖点',   file: 'console-points-1440.png',     expectTable: true },
  { name: 'coupons',    path: '/console/platform/coupons',    title: '优惠券',   file: 'console-coupons-1440.png',    expectTable: true },
  { name: 'flash',      path: '/console/platform/flash-sales', title: '秒杀场次', file: 'console-flash-1440.png',      expectTable: false },
  { name: 'orders',     path: '/console/platform/orders',     title: '平台订单', file: 'console-orders-1440.png',     expectTable: true },
  { name: 'cache',      path: '/console/platform/cache',      title: '缓存运维', file: 'console-cache-1440.png',      expectTable: true },
]
const MENU_LABELS = ['运营概览', '平台商品', '代卖点', '优惠券', '秒杀场次', '平台订单', '缓存运维']

if (DRY) {
  console.log('第 3 轮 · 独立运营台验收 · Web 脚本（DryRun）')
  console.log(`BASE=${BASE}  截图目录=${OUT_DIR}`)
  console.log(`管理员 phone=${ADMIN_PHONE}`)
  console.log('计划断言的页面：')
  for (const p of PAGES) console.log(`  - ${p.path}  → 截图 ${p.file}；表格=${p.expectTable ? '应有' : '无强制'}`)
  console.log('每页断言：.pc 根节点 / 深色侧栏(≈248px) / 侧栏不在商店布局内 / 64px 顶栏 / .pc__main 通栏宽度 / 无横向溢出 / 表格 scrollWidth<=clientWidth+2 / 标题 / 无 console.error+pageerror+API5xx')
  console.log('概览额外：首屏 KPI 大卡 >=4（.ov__kpis .kpi 计数 + 大号数字启发式）；另出 1920×1080 截图且内容宽 >1600（Lead 裁决口径）')
  console.log('兼容：/admin/platform-points → /console/platform/points；/admin 旧后台可用且含「平台运营台」入口；/platform 商城页正常')
  console.log('说明（Lead 裁决）：1440 物理上限=1440-248=1192，脚本断言「通栏≈视口-侧栏（>=1190）」+无横向溢出+表格无截断；1920×1080 断言内容宽 >1600 且通栏。结束时打印「视觉量化」表（每页实宽/侧栏/顶栏/KPI/表格 overflow）并写入 JSON。')
  process.exit(0)
}

mkdirSync(OUT_DIR, { recursive: true })

// ---------------------------------------------------------------- 结果收集
let pass = 0
let fail = 0
let skip = 0
const results = []
const layoutMetrics = []
function check(id, desc, cond, detail = '') {
  const status = cond ? 'PASS' : 'FAIL'
  if (cond) pass++
  else fail++
  results.push({ id, desc, status, detail })
  if (cond) console.log(`  [PASS] ${id} ${desc}`)
  else console.log(`  [FAIL] ${id} ${desc}  ${detail}`)
}
function skipCheck(id, desc, reason) {
  skip++
  results.push({ id, desc, status: 'SKIP', detail: reason })
  console.log(`  [SKIP] ${id} ${desc} —— ${reason}`)
}
function head(text) {
  console.log('')
  console.log(`=== ${text} ===`)
}
function brief(text, max = 360) {
  const s = String(text ?? '').replace(/\s+/g, ' ')
  return s.length > max ? s.slice(0, max) + '...' : s
}

// ---------------------------------------------------------------- token
function loadAdminToken() {
  if (process.env.WEB_ADMIN_TOKEN) return { token: process.env.WEB_ADMIN_TOKEN, from: 'env' }
  if (existsSync(TOKEN_ROUND2)) {
    try {
      const cached = JSON.parse(readFileSync(TOKEN_ROUND2, 'utf8').replace(/^\uFEFF/, ''))
      const t = cached?.[ADMIN_PHONE]?.token
      if (t) return { token: t, from: 'round2-token-cache' }
    } catch (err) {
      console.error(`[WARN] round2 token 缓存解析失败：${err.message}`)
    }
  }
  if (existsSync(TOKEN_FILE)) {
    const t = readFileSync(TOKEN_FILE, 'utf8').replace(/^\uFEFF/, '').trim()
    if (t) return { token: t, from: 'admin-token.txt' }
  }
  return { token: '', from: 'none' }
}
const adminAuth = loadAdminToken()
if (!adminAuth.token) {
  console.error('找不到管理员 token：请先跑 scripts/verify-console-api.ps1（会缓存 token），或传 WEB_ADMIN_TOKEN / 写 .build/console/admin-token.txt')
  process.exit(2)
}

// ---------------------------------------------------------------- Chrome / puppeteer
const CHROME_CANDIDATES = [
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
]
const chromePath = CHROME_CANDIDATES.find((p) => existsSync(p))
if (!chromePath) {
  console.error('找不到 Chrome/Edge，跳过（本脚本需要真实浏览器跑 Vue 应用）')
  process.exit(2)
}
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
  console.error('未安装 puppeteer-core：cd ershou-web && npm i -D puppeteer-core')
  process.exit(2)
}

// ---------------------------------------------------------------- 浏览器与工具
const pageErrors = []
const browser = await puppeteer.default.launch({
  executablePath: chromePath,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1440,960'],
})
function attachCollectors(page, tag, bucket) {
  page.on('pageerror', (err) => bucket.push(`[${tag}][pageerror] ${brief(err.message, 240)}`))
  page.on('console', (msg) => {
    if (msg.type() === 'error') bucket.push(`[${tag}][console.error] ${brief(msg.text(), 240)}`)
  })
  page.on('response', (resp) => {
    const url = resp.url()
    if (url.includes('/api/v1/') && resp.status() >= 500) bucket.push(`[${tag}][http ${resp.status()}] ${brief(url, 180)}`)
  })
}
async function loginPage(page) {
  await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.evaluate((t) => localStorage.setItem('ershou_token', t), adminAuth.token)
}
async function openPage(browserInst, viewport, token) {
  const page = await browserInst.newPage()
  await page.setViewport(viewport)
  await attachCollectors(page, 'page', pageErrors)
  if (token) await loginPage(page)
  return page
}

// 通栏宽度/侧栏/顶栏/表格溢出/暗色侧栏 的统一测量
async function measureLayout(page) {
  return page.evaluate(() => {
    const q = (sels) => sels.map((s) => document.querySelector(s)).find(Boolean) || null
    const sidebar = q(['.pc__side', '.pc-side', 'aside.pc__side', '.pc__sidebar'])
    const main = q(['.pc__main', '.pc-main', '.pc__body', '.pc__content', 'main'])
    const root = q(['.pc', '.platform-console'])
    const topbar = q(['.pc__top', '.pc__head', '.pc__topbar'])
    const rect = (el) => { if (!el) return null; const r = el.getBoundingClientRect(); return { width: Math.round(r.width), height: Math.round(r.height), left: Math.round(r.left), right: Math.round(r.right) } }
    const style = (el) => {
      if (!el) return null
      const cs = getComputedStyle(el)
      return { backgroundImage: cs.backgroundImage, backgroundColor: cs.backgroundColor, position: cs.position }
    }
    const luminance = (bg) => {
      const m = String(bg || '').match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/)
      if (!m) return -1
      return Math.round(0.2126 * Number(m[1]) + 0.7152 * Number(m[2]) + 0.0722 * Number(m[3]))
    }
    const tables = Array.from(document.querySelectorAll('.el-table__body-wrapper, .el-table__header-wrapper')).map((el) => ({
      cls: el.className,
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
      overflow: el.scrollWidth - el.clientWidth,
    }))
    const tableRoots = document.querySelectorAll('.el-table').length
    const menuItems = document.querySelectorAll('.pc__menu-item').length
    const kpiCards = document.querySelectorAll('.ov__kpis .kpi, .kpi').length
    const contentRoot = !!document.querySelector('.pc__content')
    const pageRoot = !!(document.querySelector('.console-page') || document.querySelector('.ov'))
    // 商店布局包裹检测：console 必须独立于 DefaultLayout/AdminShell
    const wrapped = !!(root && root.closest('.default-layout, .app-header, .admin-shell, .admin'))
    // KPI 大卡启发式：class 含 kpi 或（fontSize>=28 且文本为数字/金额）
    const kpiClasses = document.querySelectorAll('[class*="kpi" i], [class*="KPI"], [class*="stat-card"], [class*="StatCard"]').length
    let bigNums = 0
    const seen = new Set()
    const mainEl = main || document.body
    for (const el of mainEl.querySelectorAll('*')) {
      const txt = (el.textContent || '').trim()
      if (!txt || txt.length > 24) continue
      const cs = getComputedStyle(el)
      const fs = parseFloat(cs.fontSize || '0')
      if (fs >= 28 && /^[¥￥]?[\d.,%+-]+$/.test(txt)) {
        const parent = el.parentElement
        const key = parent ? parent.className + '|' + txt : txt
        if (!seen.has(key)) { seen.add(key); bigNums++ }
      }
    }
    return {
      root: rect(root), sidebar: rect(sidebar), sidebarStyle: style(sidebar), main: rect(main), topbar: rect(topbar),
      sidebarLum: sidebar ? luminance(style(sidebar)?.backgroundColor) : -1,
      sidebarHasGradient: !!(style(sidebar)?.backgroundImage || '').includes('gradient'),
      tables, tableRoots, wrapped, kpiClasses, bigNums, menuItems, kpiCards, contentRoot, pageRoot,
      mainScrollWidth: main ? main.scrollWidth : -1,
      mainClientWidth: main ? main.clientWidth : -1,
      bodyScrollWidth: document.body.scrollWidth,
      menuText: sidebar ? (sidebar.textContent || '').replace(/\s+/g, ' ') : '',
      title: document.title,
      pcMainText: main ? (main.textContent || '').slice(0, 500) : '',
      viewport: { w: window.innerWidth, h: window.innerHeight },
      hasStoreLayout: !!document.querySelector('.default-layout, .app-header'),
    }
  })
}

let browserClosed = false
try {
  console.log('============================================================')
  console.log(' 第 3 轮 · 独立运营台验收 · Web 视觉/布局/兼容')
  console.log(` BASE=${BASE}  截图目录=${OUT_DIR}`)
  console.log(` 管理员 token 来源=${adminAuth.from}`)
  console.log('============================================================')

  // 预检 dev server
  {
    const probe = await browser.newPage()
    const resp = await probe.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => null)
    await probe.close()
    if (!resp) {
      console.error(`[FAIL] 前端 dev server 不可用：${BASE}（请 cd ershou-web && npm run dev，5173 独占）`)
      await browser.close()
      process.exit(2)
    }
  }

  // ================================================================ W1 七个页面（1440×900）
  head('W1 七个页面 @1440×900：通栏 / 侧栏 / 顶栏 / 无截断 / 无错误 / 截图')
  const overviewMetric = { layout: null }
  for (const p of PAGES) {
    const page = await openPage(browser, NOMINAL_VIEWPORT, true)
    const beforeErr = pageErrors.length
    await page.goto(BASE + p.path, { waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {})
    await page.waitForFunction(() => !!document.querySelector('.pc, .platform-console'), { timeout: 20000 }).catch(() => {})
    await new Promise((r) => setTimeout(r, 1200))
    const layout = await measureLayout(page).catch((err) => ({ error: err.message }))
    const hasRoot = !!(layout && layout.root)
    check(`W1-${p.name}-1`, `${p.path} 独立 Shell 渲染（.pc 根节点，无商店布局包裹）`, hasRoot && layout.wrapped === false, hasRoot ? `wrapped=${layout.wrapped}` : '页面未渲染（白屏/路由未注册/被重定向）')
    if (!hasRoot) {
      await page.screenshot({ path: path.join(OUT_DIR, `console-${p.name}-MISSING.png`), fullPage: true }).catch(() => {})
      const bodyText = await page.evaluate(() => (document.body.innerText || '').slice(0, 200)).catch(() => '')
      console.log(`        页面文本片段：${brief(bodyText)}`)
      await page.close()
      continue
    }
    if (p.name === 'overview') overviewMetric.layout = layout
    // 侧栏：存在、宽≈248、深色、含 7 个菜单
    const sb = layout.sidebar
    check(`W1-${p.name}-2`, `深色侧栏存在且宽≈${NOMINAL_SIDEBAR}px（实际 ${sb ? sb.width : 'missing'}px）`, !!sb && sb.width >= 200 && sb.width <= 320, JSON.stringify(sb))
    const dark = !!layout.sidebarHasGradient || (layout.sidebarLum >= 0 && layout.sidebarLum < 100)
    check(`W1-${p.name}-3`, `侧栏为深色（gradient=${layout.sidebarHasGradient}, 亮度=${layout.sidebarLum}）`, dark, `gradient=${layout.sidebarHasGradient}, lum=${layout.sidebarLum}`)
    const menuHit = MENU_LABELS.filter((l) => layout.menuText.includes(l))
    check(`W1-${p.name}-4`, `侧栏含 7 个菜单项（.pc__menu-item=${layout.menuItems}，文案命中 ${menuHit.length}/7）`, layout.menuItems >= 7 && menuHit.length >= 7, `items=${layout.menuItems}, hit=${menuHit.join('|')}`)
    // 内容容器与业务根节点（真实实现：.pc__content 内是 .console-page 或概览 .ov）
    check(`W1-${p.name}-13`, '内容容器 .pc__content 存在', layout.contentRoot, '未找到 .pc__content')
    check(`W1-${p.name}-14`, '业务页面根节点存在（.console-page / .ov）', layout.pageRoot, '未找到 .console-page 或 .ov')
    // 顶栏 64px
    const tb = layout.topbar
    check(`W1-${p.name}-5`, `顶部工具栏存在且高≈64px（实际 ${tb ? tb.height : 'missing'}px）`, !!tb && tb.height >= 50 && tb.height <= 90, JSON.stringify(tb))
    // 内容区通栏：宽度 ≈ 视口 - 侧栏
    const expectWidth = layout.viewport.w - (sb ? sb.width : NOMINAL_SIDEBAR)
    const mainW = layout.main ? layout.main.width : 0
    check(`W1-${p.name}-6`, `内容区通栏：main+侧栏≈视口宽（视口 ${layout.viewport.w} - 侧栏 ${sb ? sb.width : '?'} = ${expectWidth}，实测 main=${mainW}）`, mainW >= expectWidth - 3, `main=${mainW}, expect=${expectWidth}`)
    // 无横向溢出（内容区 / body）
    check(`W1-${p.name}-7`, `内容区无横向溢出（main.scrollWidth=${layout.mainScrollWidth} <= clientWidth=${layout.mainClientWidth}+2）`, layout.mainScrollWidth <= layout.mainClientWidth + 2, `scroll=${layout.mainScrollWidth}, client=${layout.mainClientWidth}`)
    check(`W1-${p.name}-8`, `页面无整页横向滚动（body.scrollWidth=${layout.bodyScrollWidth} <= 视口 ${layout.viewport.w}+2）`, layout.bodyScrollWidth <= layout.viewport.w + 2, `body=${layout.bodyScrollWidth}`)
    // 表格无横向截断
    const badTables = layout.tables.filter((t) => t.overflow > 2)
    if (layout.tables.length > 0) {
      check(`W1-${p.name}-9`, `表格无横向截断（${layout.tables.length} 个 .el-table wrapper，溢出>2 的 ${badTables.length} 个）`, badTables.length === 0, JSON.stringify(badTables))
    } else if (p.expectTable) {
      check(`W1-${p.name}-9`, `页面应有表格（.el-table）但未找到`, false, `tableRoots=${layout.tableRoots}`)
    } else {
      skipCheck(`W1-${p.name}-9`, '表格横向截断', '本页无表格（卡片式）')
    }
    // 标题
    check(`W1-${p.name}-10`, `页面标题含「${p.title}」（实际 "${layout.title}"）`, layout.title.includes(p.title), layout.title)
    // 截图
    await page.screenshot({ path: path.join(OUT_DIR, p.file), fullPage: false })
    check(`W1-${p.name}-11`, `1440×900 截图已保存 ${p.file}`, existsSync(path.join(OUT_DIR, p.file)), '')
    console.log(`        [实测] viewport=${layout.viewport.w}x${layout.viewport.h} sidebar=${sb ? sb.width : '?'} main=${mainW} bodyScroll=${layout.bodyScrollWidth} tableRoots=${layout.tableRoots} wrapped=${layout.wrapped}`)
    layoutMetrics.push({
      page: p.name, path: p.path, viewport: `${layout.viewport.w}x${layout.viewport.h}`,
      sidebarW: sb ? sb.width : null, topbarH: tb ? tb.height : null, mainW,
      mainScrollW: layout.mainScrollWidth, mainClientW: layout.mainClientWidth,
      bodyScrollW: layout.bodyScrollWidth, tableCount: layout.tables.length,
      tableMaxOverflow: layout.tables.length ? Math.max(...layout.tables.map((t) => t.overflow)) : 0,
      tableOverflowOk: badTables.length === 0, kpiCount: layout.kpiCards,
      menuItems: layout.menuItems, wrapped: layout.wrapped,
    })
    // 错误
    const pageErrs = pageErrors.slice(beforeErr)
    check(`W1-${p.name}-12`, `页面无 console.error / pageerror / API 5xx（${pageErrs.length} 条）`, pageErrs.length === 0, brief(pageErrs.slice(0, 3).join(' | '), 700))
    await page.close()
  }

  // ================================================================ W2 概览 KPI + 1920×1080
  head('W2 概览首屏 KPI 卡 + 1920×1080 大屏')
  {
    const ovLayout = overviewMetric.layout
    if (ovLayout && ovLayout.root) {
      const kpi = Math.max(ovLayout.kpiCards || 0, ovLayout.bigNums || 0, ovLayout.kpiClasses || 0)
      check('W2-1', `概览首屏 KPI 大卡 >=4（.kpi 卡 ${ovLayout.kpiCards} 个，大号数字 ${ovLayout.bigNums} 个）`, kpi >= 4, `kpiCards=${ovLayout.kpiCards}, bigNums=${ovLayout.bigNums}`)
    } else { check('W2-1', '概览首屏 KPI 大卡 >=4', false, '概览页未渲染（W1 已失败）') }
    const page = await openPage(browser, BIG_VIEWPORT, true)
    const beforeErr = pageErrors.length
    await page.goto(BASE + '/console/platform', { waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {})
    await page.waitForFunction(() => !!document.querySelector('.pc, .platform-console'), { timeout: 20000 }).catch(() => {})
    await new Promise((r) => setTimeout(r, 1200))
    const layout = await measureLayout(page).catch((err) => ({ error: err.message }))
    const mainW = layout.main ? layout.main.width : 0
    const sbW = layout.sidebar ? layout.sidebar.width : NOMINAL_SIDEBAR
    check('W2-2', `1920×1080 内容宽 >1600（实测 main=${mainW}）`, mainW > 1600, `main=${mainW}`)
    check('W2-3', `1920×1080 通栏（main=${mainW} ≈ 1920-侧栏 ${sbW}）`, mainW >= 1920 - sbW - 3, `main=${mainW}, expect=${1920 - sbW}`)
    check('W2-4', `1920×1080 无横向溢出（main.scroll=${layout.mainScrollWidth} <= ${layout.mainClientWidth}+2）`, layout.mainScrollWidth <= layout.mainClientWidth + 2, '')
    await page.screenshot({ path: path.join(OUT_DIR, 'console-overview-1920.png'), fullPage: false })
    check('W2-5', '1920×1080 概览截图已保存 console-overview-1920.png', existsSync(path.join(OUT_DIR, 'console-overview-1920.png')), '')
    const errs = pageErrors.slice(beforeErr)
    check('W2-6', `1920 概览无 console.error / pageerror / API 5xx（${errs.length} 条）`, errs.length === 0, brief(errs.slice(0, 3).join(' | ')))
    console.log(`        [实测] viewport=${layout.viewport.w}x${layout.viewport.h} sidebar=${sbW} main=${mainW}`)
    layoutMetrics.push({
      page: 'overview-1920',
      path: '/console/platform',
      viewport: `${layout.viewport.w}x${layout.viewport.h}`,
      sidebarW: sbW,
      topbarH: layout.topbar ? layout.topbar.height : null,
      mainW,
      mainScrollW: layout.mainScrollWidth,
      mainClientW: layout.mainClientWidth,
      bodyScrollW: layout.bodyScrollWidth,
      tableCount: layout.tables.length,
      tableMaxOverflow: layout.tables.length ? Math.max(...layout.tables.map((t) => t.overflow)) : 0,
      tableOverflowOk: layout.tables.filter((t) => t.overflow > 2).length === 0,
      kpiCount: layout.kpiCards,
      menuItems: layout.menuItems,
      wrapped: layout.wrapped,
    })
    await page.close()
  }

  // ================================================================ W3 兼容
  head('W3 兼容：旧地址 redirect / 旧后台 / 商城页')
  {
    // 3.1 /admin/platform-points → /console/platform/points
    const page = await openPage(browser, NOMINAL_VIEWPORT, true)
    const beforeErr = pageErrors.length
    await page.goto(BASE + '/admin/platform-points', { waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {})
    const redirected = await page.waitForFunction(() => location.pathname === '/console/platform/points', { timeout: 15000 }).then(() => true).catch(() => false)
    const finalPath = await page.evaluate(() => location.pathname).catch(() => '')
    check('W3-1', `/admin/platform-points 自动跳转 /console/platform/points（实际 ${finalPath}）`, redirected, finalPath)
    const onPoints = await page.evaluate(() => !!document.querySelector('.pc, .platform-console')).catch(() => false)
    check('W3-2', '跳转后渲染运营台代卖点页（.pc 存在）', onPoints, '未渲染 .pc')
    await page.screenshot({ path: path.join(OUT_DIR, 'console-compat-legacy-redirect.png'), fullPage: false }).catch(() => {})
    const errs1 = pageErrors.slice(beforeErr)
    check('W3-3', `旧地址跳转过程无 console.error / pageerror（${errs1.length} 条）`, errs1.length === 0, brief(errs1.slice(0, 3).join(' | ')))
    await page.close()

    // 3.2 /admin 旧后台可用 + 有「平台运营台」入口
    const page2 = await openPage(browser, NOMINAL_VIEWPORT, true)
    const beforeErr2 = pageErrors.length
    await page2.goto(BASE + '/admin', { waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {})
    await new Promise((r) => setTimeout(r, 1500))
    const adminState = await page2.evaluate(() => ({
      path: location.pathname,
      text: (document.body.innerText || '').replace(/\s+/g, ' ').slice(0, 4000),
      hasAdminRoot: !!document.querySelector('.admin, .admin-page, .admin-shell'),
      hasEntry: (document.body.innerText || '').includes('平台运营台'),
    })).catch(() => ({ path: '', text: '', hasAdminRoot: false, hasEntry: false }))
    check('W3-4', `/admin 旧后台可访问（path=${adminState.path}）`, adminState.path.startsWith('/admin') && adminState.path !== '/login', adminState.path)
    check('W3-5', `/admin 旧后台正常渲染（.admin 根节点）`, adminState.hasAdminRoot, '未渲染 admin 根节点')
    check('W3-6', `旧后台含「平台运营台」入口按钮（文本命中）`, adminState.hasEntry, brief(adminState.text, 300))
    await page2.screenshot({ path: path.join(OUT_DIR, 'console-compat-admin-legacy.png'), fullPage: false }).catch(() => {})
    const errs2 = pageErrors.slice(beforeErr2)
    check('W3-7', `/admin 旧后台无 console.error / pageerror（${errs2.length} 条）`, errs2.length === 0, brief(errs2.slice(0, 3).join(' | ')))
    await page2.close()

    // 3.3 /platform 商城页正常
    const page3 = await openPage(browser, NOMINAL_VIEWPORT, true)
    const beforeErr3 = pageErrors.length
    await page3.goto(BASE + '/platform', { waitUntil: 'networkidle2', timeout: 30000 }).catch(() => {})
    await page3.waitForFunction(() => !!document.querySelector('.platform'), { timeout: 20000 }).catch(() => {})
    const shopState = await page3.evaluate(() => ({
      hasPlatform: !!document.querySelector('.platform'),
      hero: document.querySelector('.hero__title')?.textContent?.trim() || '',
      entry: (document.body.innerText || '').includes('运营台'),
      cards: document.querySelectorAll('#items .card').length,
    })).catch(() => ({ hasPlatform: false, hero: '', entry: false, cards: 0 }))
    check('W3-8', `/platform 商城页正常（.platform 根节点）`, shopState.hasPlatform, '未渲染')
    check('W3-9', `商城 hero 标题含「官方严选」（"${shopState.hero}"）`, shopState.hero.includes('官方严选'), shopState.hero)
    check('W3-10', `商城页对管理员显示「运营台」入口（文本命中=${shopState.entry}）`, shopState.entry, '未找到运营台入口（设计 4.5：PlatformView 对 isAdmin 显示）')
    await page3.screenshot({ path: path.join(OUT_DIR, 'console-compat-shop-platform.png'), fullPage: false }).catch(() => {})
    const errs3 = pageErrors.slice(beforeErr3)
    check('W3-11', `/platform 商城页无 console.error / pageerror（${errs3.length} 条）`, errs3.length === 0, brief(errs3.slice(0, 3).join(' | ')))
    await page3.close()
  }

  // ================================================================ W4 汇总
  head('W4 汇总（页面级错误总表）')
  check('W4-1', `全流程页面运行期错误总数（pageerror/console.error/API5xx）= ${pageErrors.length}`, pageErrors.length === 0, brief(pageErrors.slice(0, 5).join(' | '), 900))
} finally {
  if (!browserClosed) {
    browserClosed = true
    await browser.close().catch(() => {})
  }
  const outFile = path.join(OUT_DIR, 'console-web-result.json')
  writeFileSync(
    outFile,
    JSON.stringify({
      at: new Date().toISOString(),
      base: BASE,
      adminTokenFrom: adminAuth.from,
      standardViewport: NOMINAL_VIEWPORT,
      bigViewport: BIG_VIEWPORT,
      pass, fail, skip,
      results,
      pageErrors,
      layoutMetrics,
    }, null, 2),
    'utf8',
  )
  console.log('')
  console.log('=== 视觉量化（供验收报告「大气」证据直接引用）===')
  console.log('page            viewport   sidebar  topbar  mainW   mainScroll  bodyScroll  tables  maxOverflow  kpi  menu  wrapped')
  for (const m of layoutMetrics) {
    console.log([
      String(m.page).padEnd(15),
      String(m.viewport).padEnd(10),
      String(m.sidebarW ?? '-').padEnd(8),
      String(m.topbarH ?? '-').padEnd(7),
      String(m.mainW).padEnd(7),
      String(m.mainScrollW).padEnd(11),
      String(m.bodyScrollW).padEnd(11),
      String(m.tableCount).padEnd(7),
      String(m.tableMaxOverflow).padEnd(12),
      String(m.kpiCount).padEnd(4),
      String(m.menuItems).padEnd(5),
      String(m.wrapped),
    ].join(' '))
  }
  console.log('')
  console.log('------------------------------------------------------------')
  if (fail === 0) console.log(`Web 验收全部通过：${pass} 项断言（SKIP ${skip}），结果 ${outFile}`)
  else console.log(`Web 验收：通过 ${pass} 项，失败 ${fail} 项，SKIP ${skip}；结果 ${outFile}`)
  console.log('------------------------------------------------------------')
  process.exit(fail)
}
