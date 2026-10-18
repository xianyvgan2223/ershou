// 第 2 轮「平台代卖」验收 · Web 页面截图与 DOM 断言脚本
//
// 对应 `智能体团队/第2轮-平台代卖-设计.md` 第七节验收标准 6：
//   - `/platform` 页面（秒杀区 / 优惠券区 / 代卖点 chips / 平台商品区）
//   - 首页「平台严选」入口 + 商品卡「官方代卖」金色角标
//   - `/admin/platform-points` 后台代卖点管理（表格 / 新建弹窗）
//
// 依赖：puppeteer-core（已在 ershou-web/node_modules）+ 本机 Chrome；
//       前端 dev server（默认 http://localhost:5173）+ 后端 8080（数据来源）。
//
// 用法：
//   node scripts/verify-platform-web.mjs
//   WEB_BASE=http://localhost:5173 node scripts/verify-platform-web.mjs
//   WEB_TOKEN=xxx WEB_ADMIN_TOKEN=yyy node scripts/verify-platform-web.mjs
//
// token 来源优先级：
//   1) env WEB_TOKEN / WEB_ADMIN_TOKEN
//   2) .build/qa/platform-qa-tokens.json（verify-platform-api.ps1 跑完后写入的缓存，
//      买家 19900000101 / 管理员 19900000102）
// 截图输出：.build/qa/web-platform.png、web-platform-flash.png、web-home-platform-entry.png、
//           web-home-official-badge.png、web-admin-platform-points.png、web-admin-point-dialog.png
//
// 退出码：0=全通过；2=环境缺失（Chrome/puppeteer/dev server）；>0=失败断言数。

import { existsSync, readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const SHOT_DIR = path.join(ROOT, '.build', 'qa')
const TOKEN_CACHE = path.join(SHOT_DIR, 'platform-qa-tokens.json')
const BASE = (process.env.WEB_BASE ?? 'http://localhost:5173').replace(/\/$/, '')

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

// puppeteer-core 装在 ershou-web 下，从脚本目录向上找
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

// —— token：env 优先，其次 API 脚本写的缓存（买家 19900000101 / 管理员 19900000102）
// 注意：Windows PowerShell 5.1 的 `Set-Content -Encoding UTF8` 会写 UTF-8 **带 BOM**，
// 直接 JSON.parse 会抛 "Unexpected token ﻿ in JSON at position 0" → 必须先剥掉 BOM。
let cached = {}
if (existsSync(TOKEN_CACHE)) {
  try {
    cached = JSON.parse(readFileSync(TOKEN_CACHE, 'utf8').replace(/^\uFEFF/, ''))
  } catch (err) {
    console.error(`[WARN] token 缓存解析失败（${err.message}），改为匿名模式；可传 WEB_TOKEN/WEB_ADMIN_TOKEN`)
    cached = {}
  }
}
const BUYER_PHONE = process.env.WEB_PHONE ?? '19900000101'
const ADMIN_PHONE = process.env.WEB_ADMIN_PHONE ?? '19900000102'
const buyerToken = process.env.WEB_TOKEN ?? cached?.[BUYER_PHONE]?.token ?? ''
const adminToken = process.env.WEB_ADMIN_TOKEN ?? cached?.[ADMIN_PHONE]?.token ?? ''

mkdirSync(SHOT_DIR, { recursive: true })

let pass = 0
let fail = 0
let skip = 0
const results = []
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
function brief(text, max = 400) {
  const s = String(text ?? '')
  return s.length > max ? s.slice(0, max) + '...' : s
}

// 页面级错误收集（console error / uncaught pageerror）——任何一条都算 FAIL，避免"页面白屏但断言看不见"
function attachErrorCollector(page, tag, bucket) {
  page.on('pageerror', (err) => bucket.push(`[${tag}][pageerror] ${err.message}`))
  page.on('console', (msg) => {
    if (msg.type() === 'error') bucket.push(`[${tag}][console.error] ${brief(msg.text(), 240)}`)
  })
  page.on('response', (resp) => {
    const url = resp.url()
    if (url.includes('/api/v1/') && resp.status() >= 500) {
      bucket.push(`[${tag}][http ${resp.status()}] ${brief(url, 200)}`)
    }
  })
}

async function newPage(browser, token, useNewContext) {
  const context = useNewContext ? await browser.createBrowserContext() : browser.defaultBrowserContext()
  const page = await context.newPage()
  await page.setViewport({ width: 1440, height: 960 })
  if (token) {
    await page.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 20000 })
    await page.evaluate((t) => {
      // KEY 必须与 web/src/utils/request.ts 的 TOKEN_KEY 一致：ershou_token（下划线）
      localStorage.setItem('ershou_token', t)
    }, token)
  }
  return { page, context }
}

const errors = []
const browser = await puppeteer.default.launch({
  executablePath: chromePath,
  headless: 'new',
  args: ['--no-sandbox', '--disable-gpu', '--window-size=1440,960'],
})

// 先确认 dev server 在跑（否则给出明确提示，不产一堆误导性 FAIL）
try {
  const probe = await browser.newPage()
  const resp = await probe.goto(BASE, { waitUntil: 'domcontentloaded', timeout: 15000 }).catch(() => null)
  await probe.close()
  if (!resp) throw new Error('no response')
} catch (err) {
  console.error(`[FAIL] 前端 dev server 不可用：${BASE}（${err.message}）`)
  console.error('  请先启动：cd ershou-web && npm run dev（5173）')
  await browser.close()
  process.exit(2)
}

try {
  console.log('============================================================')
  console.log(' 第 2 轮 · 平台代卖验收 · Web 页面')
  console.log(` BASE=${BASE}`)
  console.log(` 截图目录=${SHOT_DIR}`)
  console.log(` 买家 token=${buyerToken ? '已注入' : '无（公开页可看，领券按钮会提示登录）'} / 管理员 token=${adminToken ? '已注入' : '无（后台页将跳过）'}`)
  console.log('============================================================')

  // ================================================================ 1. /platform 平台严选专区
  head('W1 /platform 平台严选专区')
  {
    const { page } = await newPage(browser, buyerToken, false)
    attachErrorCollector(page, 'platform', errors)
    await page.goto(`${BASE}/platform`, { waitUntil: 'networkidle2', timeout: 30000 })
    await page.waitForFunction(() => !!document.querySelector('.platform'), { timeout: 20000 }).catch(() => {})
    const hasRoot = (await page.$('.platform')) !== null
    check('W1-1', '/platform 页面渲染（.platform 根节点存在）', hasRoot, '页面未渲染（白屏/路由未注册？）')
    if (!hasRoot) {
      await page.screenshot({ path: path.join(SHOT_DIR, 'web-platform-FAILED.png'), fullPage: true })
    } else {
      const title = await page.title()
      check('W1-2', `页面标题含「平台严选」（实际 "${title}"）`, title.includes('平台严选'), title)
      const heroTitle = await page.$eval('.hero__title', (el) => el.textContent.trim()).catch(() => '')
      check('W1-3', `hero 标题 =「校园二手 · 官方严选」（实际 "${heroTitle}"）`, heroTitle.includes('官方严选'), heroTitle)
      const statCount = (await page.$$('.hero__stats .stat')).length
      check('W1-4', `hero 概览 3 个统计块（实际 ${statCount}）`, statCount === 3, String(statCount))

      // 代卖点 chips：全部代卖点 + 3 个种子点位
      const chips = await page.$$eval('.point-chip', (els) => els.map((el) => el.textContent.trim()))
      check('W1-5', `代卖点 chips >= 4（全部 + 3 个种子点位，实际 ${chips.length}）`, chips.length >= 4, JSON.stringify(chips))
      check('W1-6', '存在「全部代卖点」chip', chips.some((c) => c.includes('全部代卖点')), JSON.stringify(chips))

      // 秒杀区
      const flashCards = await page.$$eval('#flash .flash-card', (els) =>
        els.map((el) => ({
          title: el.querySelector('.flash-card__title')?.textContent.trim() ?? '',
          bar: el.querySelector('.flash-card__bar-text')?.textContent.trim() ?? '',
          btn: el.querySelector('.flash-card__btn')?.textContent.trim() ?? '',
          price: el.querySelector('.flash-card__price .price')?.textContent.trim() ?? '',
        })),
      )
      const emptyFlash = await page.$eval('#flash .empty-block__title', (el) => el.textContent.trim()).catch(() => '')
      check('W1-7', `秒杀区渲染 ${flashCards.length} 个商品卡（种子应 2 个；空态="${emptyFlash}"）`, flashCards.length >= 2, JSON.stringify(flashCards))
      if (flashCards.length > 0) {
        check('W1-8', `秒杀卡片有库存进度文案（实际 "${flashCards[0].bar}"）`, /剩余\s*\d+\/\d+/.test(flashCards[0].bar), flashCards[0].bar)
        check('W1-9', `秒杀卡片有抢购按钮（实际 "${flashCards[0].btn}"）`, flashCards[0].btn.length > 0, flashCards[0].btn)
      }
      const countdown = await page.$eval('.flash__countdown-value', (el) => el.textContent.trim()).catch(() => '')
      // 场次可能超过 1 天，PlatformView 的 formatCountdown 会输出「N天 HH:MM:SS」
      check('W1-10', `秒杀倒计时格式（N天 ）HH:MM:SS（实际 "${countdown}"）`, /^(\d+天\s+)?\d{2}:\d{2}:\d{2}$/.test(countdown), countdown)

      // 优惠券区
      const coupons = await page.$$eval('#coupons .coupon', (els) =>
        els.map((el) => ({
          name: el.querySelector('.coupon__name')?.textContent.trim() ?? '',
          face: el.querySelector('.coupon__face b')?.textContent.trim() ?? '',
          rules: el.querySelector('.coupon__face span')?.textContent.trim() ?? '',
          btn: el.querySelector('.coupon__btn')?.textContent.trim() ?? '',
        })),
      )
      check('W1-11', `优惠券区渲染 >= 3 张（实际 ${coupons.length}）`, coupons.length >= 3, JSON.stringify(coupons.map((c) => c.name)))
      if (coupons.length > 0) {
        check('W1-12', `券面额/规则有值（"${coupons[0].face}" / "${coupons[0].rules}"）`, coupons[0].face.length > 0 && coupons[0].rules.length > 0, JSON.stringify(coupons[0]))
        check('W1-13', `券按钮文案（已领/立即领取，实际 "${coupons[0].btn}"）`, coupons[0].btn.length > 0, coupons[0].btn)
      }

      // 平台商品区
      const itemCards = await page.$$eval('#items .card', (els) =>
        els.map((el) => ({
          title: el.querySelector('.card__title')?.textContent.trim() ?? '',
          official: el.querySelector('.official')?.textContent.trim() ?? '',
        })),
      )
      check('W1-14', `平台商品区渲染 >= 4 张卡（实际 ${itemCards.length}）`, itemCards.length >= 4, JSON.stringify(itemCards.map((c) => c.title).slice(0, 6)))
      const officialCards = itemCards.filter((c) => c.official.includes('官方代卖'))
      check('W1-15', `平台商品卡带「官方代卖」角标（${officialCards.length}/${itemCards.length}）`, officialCards.length >= 4, JSON.stringify(itemCards.slice(0, 6)))

      // 截图：整页 + 秒杀区
      await page.screenshot({ path: path.join(SHOT_DIR, 'web-platform.png'), fullPage: true })
      const flashEl = await page.$('#flash')
      if (flashEl) {
        await flashEl.screenshot({ path: path.join(SHOT_DIR, 'web-platform-flash.png') }).catch(() => {})
      }
      check('W1-16', '截图已保存 .build/qa/web-platform.png', existsSync(path.join(SHOT_DIR, 'web-platform.png')), '')
    }
    await page.close()
  }

  // ================================================================ 2. 首页入口 + 角标
  head('W2 首页「平台严选」入口 + 官方代卖角标')
  {
    const { page } = await newPage(browser, buyerToken, false)
    attachErrorCollector(page, 'home', errors)
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle2', timeout: 30000 })
    await page.waitForFunction(() => !!document.querySelector('.official-entry'), { timeout: 20000 }).catch(() => {})
    const entry = await page.$eval('.official-entry', (el) => ({
      text: el.textContent.replace(/\s+/g, ' ').trim(),
      badge: el.querySelector('.official-entry__badge')?.textContent.trim() ?? '',
    })).catch(() => null)
    check('W2-1', '首页渲染「平台严选」入口（.official-entry）', entry !== null, '入口不存在（HomeView 未加？）')
    if (entry) {
      check('W2-2', `入口文案含「平台严选」（实际 "${brief(entry.text, 80)}"）`, entry.text.includes('平台严选'), entry.text)
      check('W2-3', `入口「官方」金色角标（实际 "${entry.badge}"）`, entry.badge.includes('官方'), entry.badge)
      await page.screenshot({ path: path.join(SHOT_DIR, 'web-home-platform-entry.png'), fullPage: false })
      // 点击入口应跳到 /platform（SPA pushState 不触发 navigation 事件，所以轮询 location）
      await page.click('.official-entry').catch(() => {})
      await page.waitForFunction(() => location.pathname.includes('/platform'), { timeout: 15000 }).catch(() => {})
      const url = page.url()
      check('W2-4', `点击入口跳转 /platform（实际 ${url}）`, url.includes('/platform'), url)
    }
    // 首页卡片角标（平台商品在首页列表里 sellerType=2）
    await page.goto(`${BASE}/`, { waitUntil: 'networkidle2', timeout: 30000 })
    await page.waitForFunction(() => document.querySelectorAll('.card').length > 0, { timeout: 20000 }).catch(() => {})
    const homeCards = await page.$$eval('.card', (els) =>
      els.map((el) => ({
        title: el.querySelector('.card__title')?.textContent.trim() ?? '',
        official: el.querySelector('.official')?.textContent.trim() ?? '',
        officialSeller: el.querySelector('.seller__badge--official')?.textContent.trim() ?? '',
      })),
    )
    const homeOfficial = homeCards.filter((c) => c.official.includes('官方代卖'))
    check('W2-5', `首页卡片出现「官方代卖」金色角标（${homeOfficial.length}/${homeCards.length}）`, homeOfficial.length >= 1, JSON.stringify(homeCards.slice(0, 8)))
    const sellerOfficial = homeCards.filter((c) => c.officialSeller.includes('官方严选'))
    check('W2-6', `官方代卖卡卖家行显示「官方严选」（${sellerOfficial.length} 张）`, sellerOfficial.length >= 1, JSON.stringify(sellerOfficial.slice(0, 3)))
    if (homeOfficial.length > 0) {
      const badgeEl = await page.evaluateHandle((title) => {
        const cards = Array.from(document.querySelectorAll('.card'))
        const hit = cards.find((c) => c.querySelector('.card__title')?.textContent.trim() === title)
        return hit?.querySelector('.official') ?? null
      }, homeOfficial[0].title)
      const el = badgeEl.asElement()
      if (el) await el.screenshot({ path: path.join(SHOT_DIR, 'web-home-official-badge.png') }).catch(() => {})
    }
    await page.close()
  }

  // ================================================================ 3. /admin/platform-points 后台
  head('W3 /admin/platform-points 后台代卖点管理')
  if (!adminToken) {
    skipCheck('W3', '后台代卖点管理页', '没有管理员 token（先跑 verify-platform-api.ps1 生成 .build/qa/platform-qa-tokens.json，或传 WEB_ADMIN_TOKEN）')
  } else {
    const { page } = await newPage(browser, adminToken, true)
    attachErrorCollector(page, 'admin', errors)
    await page.goto(`${BASE}/admin/platform-points`, { waitUntil: 'networkidle2', timeout: 30000 })
    await new Promise((r) => setTimeout(r, 1500))
    const url = page.url()
    const hasRoot = (await page.$('.admin-platform-points')) !== null
    if (!hasRoot) {
      check('W3-1', `/admin/platform-points 可访问（当前 URL ${url}）`, false, '被路由守卫拦截：token 无效 / 该账号未被授予 admin(role_flags&8)')
      await page.screenshot({ path: path.join(SHOT_DIR, 'web-admin-platform-points-FAILED.png'), fullPage: true })
    } else {
      check('W3-1', '/admin/platform-points 渲染（.admin-platform-points 根节点）', true, '')
      const panelTitle = await page.$eval('.panel-title', (el) => el.textContent.trim()).catch(() => '')
      check('W3-2', `面板标题「平台代卖点」（实际 "${panelTitle}"）`, panelTitle.includes('平台代卖点'), panelTitle)
      const toolbar = await page.$$eval('.admin-toolbar button, .admin-toolbar .el-button', (els) => els.map((el) => el.textContent.trim()).filter(Boolean))
      check('W3-3', `工具栏含「新建点位」「刷新」（实际 ${JSON.stringify(toolbar)}）`, toolbar.some((t) => t.includes('新建点位')) && toolbar.some((t) => t.includes('刷新')), JSON.stringify(toolbar))
      const rowCount = (await page.$$('.admin-table .el-table__row')).length
      check('W3-4', `点位表格行数 >= 3（实际 ${rowCount}）`, rowCount >= 3, String(rowCount))
      const countText = await page.$eval('.admin-toolbar__count', (el) => el.textContent.replace(/\s+/g, ' ').trim()).catch(() => '')
      check('W3-5', `工具栏点位总数（实际 "${countText}"）`, /共\s*\d+\s*个点位/.test(countText), countText)
      await page.screenshot({ path: path.join(SHOT_DIR, 'web-admin-platform-points.png'), fullPage: true })
      check('W3-6', '截图已保存 .build/qa/web-admin-platform-points.png', existsSync(path.join(SHOT_DIR, 'web-admin-platform-points.png')), '')
      // 新建弹窗（只打开/截图/关闭，不提交，避免污染数据；CRUD 由 API 脚本覆盖）
      const buttons = await page.$$('.admin-toolbar .el-button')
      let opened = false
      for (const btn of buttons) {
        const text = await btn.evaluate((el) => el.textContent.trim())
        if (text.includes('新建点位')) {
          await btn.click()
          opened = true
          break
        }
      }
      if (opened) {
        await page.waitForFunction(() => !!document.querySelector('.el-dialog__title'), { timeout: 10000 }).catch(() => {})
        const dialogTitle = await page.$eval('.el-dialog__title', (el) => el.textContent.trim()).catch(() => '')
        check('W3-7', `点击「新建点位」弹出表单（标题 "${dialogTitle}"）`, dialogTitle.includes('新建平台代卖点'), dialogTitle)
        await page.screenshot({ path: path.join(SHOT_DIR, 'web-admin-point-dialog.png'), fullPage: true })
        const cancelBtn = await page.evaluateHandle(() => {
          const btns = Array.from(document.querySelectorAll('.el-dialog__footer .el-button'))
          return btns.find((b) => b.textContent.trim() === '取消') ?? null
        })
        const cancelEl = cancelBtn.asElement()
        if (cancelEl) await cancelEl.click().catch(() => {})
      } else {
        check('W3-7', '点击「新建点位」弹出表单', false, '找不到「新建点位」按钮')
      }
    }
    await page.close()
  }

  // ================================================================ 4. 页面运行期错误汇总
  head('W4 页面运行期错误汇总（console.error / pageerror / 5xx）')
  if (errors.length === 0) {
    check('W4-1', '三个页面无 uncaught error / console.error / API 5xx', true, '')
  } else {
    check('W4-1', `页面运行期错误 ${errors.length} 条（前 3 条见 detail）`, false, brief(errors.slice(0, 3).join(' | '), 800))
  }
} finally {
  await browser.close()
}

// 结果落盘，供验收报告引用
const outFile = path.join(SHOT_DIR, 'platform-web-result.json')
writeFileSync(
  outFile,
  JSON.stringify(
    {
      at: new Date().toISOString(),
      base: BASE,
      buyerToken: buyerToken ? 'injected' : 'none',
      adminToken: adminToken ? 'injected' : 'none',
      pass,
      fail,
      skip,
      results,
    },
    null,
    2,
  ),
  'utf8',
)

console.log('')
console.log('------------------------------------------------------------')
if (fail === 0) console.log(`Web 验收全部通过：${pass} 项断言（SKIP ${skip}），结果 ${outFile}`)
else console.log(`Web 验收：通过 ${pass} 项，失败 ${fail} 项，SKIP ${skip}；结果 ${outFile}`)
console.log('------------------------------------------------------------')
process.exit(fail)
